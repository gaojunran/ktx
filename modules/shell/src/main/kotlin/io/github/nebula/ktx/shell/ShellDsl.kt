package io.github.nebula.ktx.shell

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.time.Duration

// ------------------------------------------------------------------
// Command construction
// ------------------------------------------------------------------

/**
 * Create a shell command with automatic argument escaping.
 *
 * ```kotlin
 * val out = sh("echo", "hello").text()
 * val out2 = sh("mkdir", "Dir with spaces").text()
 * val out3 = sh("echo", RawArg("-n"), "hello").text()
 * ```
 */
fun sh(vararg parts: Any?): ShCommand {
    val formatted = parts.map { formatShellArg(it) }
    return ShCommand(formatted)
}

/**
 * Extension to create a shell command starting with this string as the command name.
 *
 * ```kotlin
 * val out = "echo".sh("hello").text()
 * ```
 */
fun String.sh(vararg args: Any?): ShCommand {
    val formatted = listOf(this) + args.map { formatShellArg(it) }
    return ShCommand(formatted)
}

private fun formatShellArg(arg: Any?): String = when (arg) {
    null -> ""
    is RawArg -> arg.value
    is Path -> ShellEscape.escape(arg.absolutePathString())
    is String -> ShellEscape.escape(arg)
    is Iterable<*> -> arg.joinToString(" ") { formatShellArg(it) }
    else -> ShellEscape.escape(arg.toString())
}

// ------------------------------------------------------------------
// ShellScope — idiomatic DSL inside a block
// ------------------------------------------------------------------

/**
 * A scope that lets you write commands with the `"cmd"("arg")` syntax.
 *
 * ```kotlin
 * shell {
 *     val out = "echo"("hello").text()
 *     val lines = "ls"().lines()
 *     val piped = ("cat"() pipe "grep"("foo")).text()
 * }
 * ```
 */
class ShellScope(
    private var workingDir: Path? = null,
    private val sharedEnv: MutableMap<String, String> = mutableMapOf(),
) {
    /** Build a command from the receiver string as the executable name, inheriting scope cwd and env. */
    operator fun String.invoke(vararg args: Any?): ShCommand {
        var cmd = sh(this, *args)
        workingDir?.let { cmd = cmd.cwd(it) }
        if (sharedEnv.isNotEmpty()) {
            cmd = cmd.env(sharedEnv)
        }
        return cmd
    }

    /** Change working directory for subsequent commands in this scope. */
    fun cd(path: Path) {
        workingDir = path
    }

    /** Change working directory for subsequent commands in this scope. */
    fun cd(path: String) {
        workingDir = java.nio.file.Paths.get(path)
    }

    /** Set an environment variable for subsequent commands in this scope. */
    fun env(key: String, value: String) {
        sharedEnv[key] = value
    }

    /** Set multiple environment variables for subsequent commands in this scope. */
    fun env(map: Map<String, String>) {
        sharedEnv.putAll(map)
    }

    /** Execute commands concurrently. */
    suspend fun all(vararg commands: ShCommand): List<ShResult> = shAll(*commands)

    /** Retry an action with fixed delay. */
    suspend fun <T> retry(
        times: Int = 3,
        delay: Duration = Duration.ZERO,
        block: suspend () -> T,
    ): T = io.github.nebula.ktx.shell.retry(times, delay, block)
}

/**
 * Run a block of shell commands with the `"cmd"("arg")` syntax.
 *
 * ```kotlin
 * shell {
 *     val branch = "git"("branch", "--show-current").text().trim()
 *     println("On branch: $branch")
 * }
 * ```
 */
suspend fun <T> shell(block: suspend ShellScope.() -> T): T =
    withContext(Dispatchers.IO) {
        ShellScope().block()
    }

// ------------------------------------------------------------------
// Concurrency
// ------------------------------------------------------------------

/**
 * Execute multiple commands concurrently and return all results.
 *
 * Stdout and stderr are captured for each command so the returned [ShResult]
 * objects contain complete output.
 *
 * ```kotlin
 * val results = shAll(
 *     sh("sleep", "1"),
 *     sh("sleep", "2"),
 * )
 * ```
 */
suspend fun shAll(vararg commands: ShCommand): List<ShResult> =
    coroutineScope {
        commands.map { async { it.stdoutPipe().stderrPipe().execute() } }.awaitAll()
    }

// ------------------------------------------------------------------
// Retry
// ------------------------------------------------------------------

/**
 * Retry an action up to [times] with a fixed [delay] between attempts.
 *
 * ```kotlin
 * val result = retry(times = 5, delay = 5.seconds) {
 *     sh("cargo", "publish").execute()
 * }
 * ```
 */
suspend fun <T> retry(
    times: Int = 3,
    delay: Duration = Duration.ZERO,
    block: suspend () -> T,
): T {
    var lastException: Throwable? = null
    repeat(times) { attempt ->
        try {
            return block()
        } catch (e: Throwable) {
            lastException = e
            if (attempt < times - 1 && delay > Duration.ZERO) {
                delay(delay)
            }
        }
    }
    throw lastException ?: IllegalStateException("retry exhausted")
}
