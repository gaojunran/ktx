package io.github.nebula.ktx.shell

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.time.Duration

/**
 * A shell command builder with immutable chainable configuration.
 *
 * Construct via the top-level [sh] function or the [String.sh] extension:
 *
 * ```kotlin
 * val out = sh("echo", "hello").text()
 * val out2 = "cat".sh("file.txt").lines()
 * ```
 */
class ShCommand internal constructor(
    private val parts: List<String>,
    private val env: Map<String, String> = emptyMap(),
    private val workingDir: Path? = null,
    private val timeout: Duration? = null,
    private val ignoreExitCode: Boolean = false,
    private val echoCommand: Boolean = false,
    @PublishedApi internal val redirectStdout: Redirect = Redirect.Inherit,
    private val redirectStderr: Redirect = Redirect.Inherit,
    private val redirectStdin: Redirect = Redirect.Inherit,
) {

    // ------------------------------------------------------------------
    // Builder chain methods (immutable)
    // ------------------------------------------------------------------

    fun env(key: String, value: String): ShCommand =
        copy(env = env + (key to value))

    fun env(map: Map<String, String>): ShCommand =
        copy(env = env + map)

    fun cwd(path: Path): ShCommand = copy(workingDir = path)
    fun cwd(path: String): ShCommand = copy(workingDir = java.nio.file.Paths.get(path))

    fun withTimeout(duration: Duration): ShCommand = copy(timeout = duration)

    fun ignoreExitCode(): ShCommand = copy(ignoreExitCode = true)

    fun echo(): ShCommand = copy(echoCommand = true)

    fun quiet(): ShCommand = copy(redirectStdout = Redirect.Null, redirectStderr = Redirect.Null)
    fun quietStdout(): ShCommand = copy(redirectStdout = Redirect.Null)
    fun quietStderr(): ShCommand = copy(redirectStderr = Redirect.Null)

    /** Capture stdout so `.text()`, `.lines()`, `.bytes()` work. */
    fun stdoutPipe(): ShCommand = copy(redirectStdout = Redirect.Capture)

    /** Capture stderr so `.text("stderr")` works. */
    fun stderrPipe(): ShCommand = copy(redirectStderr = Redirect.Capture)

    /** Redirect stdout to a file (overwrite). */
    fun stdout(file: Path): ShCommand = copy(redirectStdout = Redirect.FileOut(file))

    /** Redirect stderr to a file (overwrite). */
    fun stderr(file: Path): ShCommand = copy(redirectStderr = Redirect.FileOut(file))

    /** Redirect stdin from a file. */
    fun stdin(file: Path): ShCommand = copy(redirectStdin = Redirect.FileIn(file))

    /** Redirect stdin from a string. */
    fun stdin(text: String): ShCommand = copy(redirectStdin = Redirect.StringIn(text))

    /**
     * Pipe stdout of this command into stdin of [other].
     *
     * ```kotlin
     * val out = (sh("echo", "foo") pipe sh("grep", "foo")).text()
     * ```
     */
    infix fun pipe(other: ShCommand): ShCommand =
        other.copy(redirectStdin = Redirect.PipeFrom(this.stdoutPipe()))

    // ------------------------------------------------------------------
    // Execution
    // ------------------------------------------------------------------

    /**
     * Execute the command and await its completion.
     */
    suspend fun execute(): ShResult = withContext(Dispatchers.IO) {
        val commandLine = parts.joinToString(" ")

        if (echoCommand) {
            System.err.println("> $commandLine")
        }

        val process = startProcess(commandLine)

        timeout?.let { dur ->
            val finished = process.waitFor(dur.inWholeMilliseconds, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                throw ShellTimeoutException(commandLine, dur)
            }
        } ?: process.waitFor()

        val stdoutBytes = readStream(process.inputStream, redirectStdout)
        val stderrBytes = readStream(process.errorStream, redirectStderr)

        val result = ShResult(
            exitCode = process.exitValue(),
            stdoutBytes = stdoutBytes,
            stderrBytes = stderrBytes,
            commandLine = commandLine,
        )

        if (!ignoreExitCode && result.exitCode != 0) {
            throw ShellException(result)
        }

        result
    }

    // ------------------------------------------------------------------
    // Convenience accessors (suspend)
    // ------------------------------------------------------------------

    /** Execute and return stdout as a string. */
    suspend fun text(): String =
        if (redirectStdout == Redirect.Inherit) stdoutPipe().execute().stdout else execute().stdout

    /** Execute and return stderr as a string. */
    suspend fun textStderr(): String = stderrPipe().execute().stderr

    /** Execute and return stdout lines. */
    suspend fun lines(): List<String> =
        if (redirectStdout == Redirect.Inherit) stdoutPipe().execute().lines() else execute().lines()

    /** Execute and return stdout bytes. */
    suspend fun bytes(): ByteArray =
        if (redirectStdout == Redirect.Inherit) stdoutPipe().execute().stdoutBytes else execute().stdoutBytes

    /** Execute and return the exit code (does not throw on non-zero). */
    suspend fun exitCode(): Int = ignoreExitCode().execute().exitCode

    /** Execute and decode stdout as JSON. */
    suspend inline fun <reified T> json(): T =
        if (redirectStdout == Redirect.Inherit) stdoutPipe().execute().json<T>() else execute().json<T>()

    // ------------------------------------------------------------------
    // Spawning (streaming)
    // ------------------------------------------------------------------

    /**
     * Spawn the process without awaiting. Returns a [ShChild] for streaming control.
     */
    fun spawn(): ShChild {
        val commandLine = parts.joinToString(" ")

        if (echoCommand) {
            System.err.println("> $commandLine")
        }

        val pb = buildProcessBuilder(commandLine)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)

        val process = pb.start()
        return ShChild(process, commandLine)
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private suspend fun startProcess(commandLine: String): Process {
        val pb = buildProcessBuilder(commandLine)
        val process = pb.start()

        // Handle pipe: if stdin is PipeFrom, run the upstream and feed its stdout
        if (redirectStdin is Redirect.PipeFrom) {
            val upstreamResult = redirectStdin.upstream.execute()
            process.outputStream.use { os ->
                os.write(upstreamResult.stdoutBytes)
                os.flush()
            }
        }

        // Handle string stdin
        if (redirectStdin is Redirect.StringIn) {
            process.outputStream.use { os ->
                os.write(redirectStdin.text.toByteArray())
                os.flush()
            }
        }

        return process
    }

    private fun buildProcessBuilder(commandLine: String): ProcessBuilder {
        val shell = resolveShell()
        return ProcessBuilder(shell + commandLine).apply {
            environment().putAll(env)
            workingDir?.let { directory(it.toFile()) }

            when (redirectStdout) {
                Redirect.Inherit -> redirectOutput(ProcessBuilder.Redirect.INHERIT)
                Redirect.Null -> redirectOutput(ProcessBuilder.Redirect.DISCARD)
                Redirect.Capture, Redirect.Pipe -> redirectOutput(ProcessBuilder.Redirect.PIPE)
                is Redirect.FileOut -> redirectOutput(redirectStdout.path.toFile())
                else -> redirectOutput(ProcessBuilder.Redirect.INHERIT)
            }

            when (redirectStderr) {
                Redirect.Inherit -> redirectError(ProcessBuilder.Redirect.INHERIT)
                Redirect.Null -> redirectError(ProcessBuilder.Redirect.DISCARD)
                Redirect.Capture, Redirect.Pipe -> redirectError(ProcessBuilder.Redirect.PIPE)
                is Redirect.FileOut -> redirectError(redirectStderr.path.toFile())
                else -> redirectError(ProcessBuilder.Redirect.INHERIT)
            }

            when (redirectStdin) {
                Redirect.Inherit -> redirectInput(ProcessBuilder.Redirect.INHERIT)
                Redirect.Null -> redirectInput(ProcessBuilder.Redirect.DISCARD)
                Redirect.Capture -> redirectInput(ProcessBuilder.Redirect.PIPE)
                is Redirect.FileIn -> redirectInput(redirectStdin.path.toFile())
                is Redirect.StringIn, is Redirect.PipeFrom -> redirectInput(ProcessBuilder.Redirect.PIPE)
                else -> redirectInput(ProcessBuilder.Redirect.INHERIT)
            }
        }
    }

    private fun readStream(stream: java.io.InputStream, redirect: Redirect): ByteArray {
        return if (redirect == Redirect.Capture || redirect == Redirect.Pipe) {
            stream.use { it.readAllBytes() }
        } else {
            byteArrayOf()
        }
    }

    private fun resolveShell(): List<String> = when {
        System.getProperty("os.name")?.contains("Windows", ignoreCase = true) == true ->
            listOf("cmd.exe", "/c")
        else -> listOf("/bin/sh", "-c")
    }

    private fun copy(
        parts: List<String> = this.parts,
        env: Map<String, String> = this.env,
        workingDir: Path? = this.workingDir,
        timeout: Duration? = this.timeout,
        ignoreExitCode: Boolean = this.ignoreExitCode,
        echoCommand: Boolean = this.echoCommand,
        redirectStdout: Redirect = this.redirectStdout,
        redirectStderr: Redirect = this.redirectStderr,
        redirectStdin: Redirect = this.redirectStdin,
    ): ShCommand = ShCommand(
        parts, env, workingDir, timeout,
        ignoreExitCode, echoCommand,
        redirectStdout, redirectStderr, redirectStdin,
    )
}
