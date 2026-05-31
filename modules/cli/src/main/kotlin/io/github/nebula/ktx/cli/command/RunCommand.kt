package io.github.nebula.ktx.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.github.nebula.ktx.cli.ToolchainDispatcher
import io.github.nebula.ktx.cli.ipc.DaemonLifecycle
import io.github.nebula.ktx.cli.meta.ToolchainHeaderScanner
import io.github.nebula.ktx.core.deps.FrozenResolver
import io.github.nebula.ktx.core.deps.Lockfile
import io.github.nebula.ktx.core.deps.LockingFlow
import io.github.nebula.ktx.core.exec.ScriptRunner
import io.github.nebula.ktx.core.exec.printReports
import io.github.nebula.ktx.toolchain.ToolchainStore
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.system.exitProcess

/**
 * `ktx run <script> [script-args...]`
 *
 * When `<script>` is `-`, read script source from stdin (similar to
 * `uv run -` / `bun run -`).
 *
 * Options:
 *   - `--frozen`: resolve dependencies only from `<script>.lock`, no network
 *     access (CI-friendly); errors out if no lockfile is present.
 *   - `--lock`: resolve online and write/refresh `<script>.lock` before
 *     running the script normally.
 *   - `--daemon` / env var `KTX_DAEMON=1`: run via the ktx daemon, avoiding
 *     compiler cold start (since Phase 2.1). The daemon is auto-forked when
 *     not already running.
 *
 * `--frozen` / `--lock` are mutually exclusive with `--daemon` (Phase 2.1
 * daemon does not yet support custom resolver injection; Phase 2.2 will).
 */
class RunCommand : CliktCommand(name = "run") {

    private val log = LoggerFactory.getLogger("ktx.cli.run")

    private val frozen by option("--frozen", help = "Resolve only from lockfile; no network").flag()
    private val refreshLock by option("--lock", help = "Run online and refresh the lockfile").flag()
    private val daemon by option(
        "--daemon",
        help = "Run via ktx daemon (eliminates compiler cold start). Also enabled by KTX_DAEMON=1.",
    ).flag(default = System.getenv("KTX_DAEMON") == "1")

    private val forwardStdin by option(
        "--forward-stdin",
        help = "Stream ktx's own stdin to the script in the daemon (off by default; " +
            "enabling this delays process shutdown by ~300ms while waiting for stdin reads to unblock).",
    ).flag(default = System.getenv("KTX_FORWARD_STDIN") == "1")

    private val scriptArg by argument(
        name = "SCRIPT",
        help = "Script path, or `-` to read from stdin",
    )
    private val scriptArgs by argument(name = "ARGS", help = "Arguments forwarded to the script").multiple()

    override fun run() {
        require(!(frozen && refreshLock)) { "--frozen and --lock are mutually exclusive" }

        if (daemon) {
            runViaDaemon()
            return  // unreachable
        }

        val runner = ScriptRunner()
        val result = when {
            scriptArg == "-" -> {
                require(!frozen) { "stdin mode does not support --frozen (no lockfile to bind to)" }
                require(!refreshLock) { "stdin mode does not support --lock" }
                val source = System.`in`.bufferedReader().readText()
                runner.runInline(source, scriptArgs.toTypedArray(), virtualName = "<stdin>")
            }
            else -> runFile(runner)
        }
        result.printReports()
        exitProcess(if (result is ResultWithDiagnostics.Success) 0 else 1)
    }

    private fun runViaDaemon(): Nothing {
        val (jdkMajor, javaBin) = pickToolchain()
        val client = DaemonLifecycle.ensureRunning(jdkMajor, javaBin)
        val (resolverMode, lockfilePath) = pickResolver()

        val exitCode = if (scriptArg == "-") {
            // `ktx run -` semantics: read script source from stdin and run inline.
            // stdin is consumed entirely as source; it cannot also be passed
            // to the script as data. This has been the behavior since Phase 1.
            val source = System.`in`.bufferedReader().readText()
            client.run(
                scriptPath = null,
                inlineSource = source,
                scriptArgs = scriptArgs,
                virtualName = "<stdin>",
                resolverMode = io.github.nebula.ktx.proto.v1.ResolverMode.NORMAL,
            )
        } else {
            val path = resolveScript(scriptArg)
            preflightToolchain(path)
            // Phase 2.3: forward ktx's own stdin to the daemon as a stream.
            // Disabled by default: the pump thread keeps a stdin read in
            // a native syscall during shutdown, adding ~300ms to exit.
            // Users who need it must opt in via --forward-stdin or
            // KTX_FORWARD_STDIN=1.
            client.run(
                scriptPath = path,
                inlineSource = null,
                scriptArgs = scriptArgs,
                resolverMode = resolverMode,
                lockfilePath = lockfilePath,
                stdin = if (forwardStdin) System.`in` else null,
            )
        }
        exitProcess(exitCode)
    }

    /**
     * Translate CLI options into a daemon-protocol ResolverMode plus an
     * optional lockfile path.
     *
     * Since Phase 2.3: `--lock` uses the daemon's LOCK mode, which runs the
     * script once with the compiler cache disabled and writes the lockfile
     * afterwards.
     */
    private fun pickResolver(): Pair<io.github.nebula.ktx.proto.v1.ResolverMode, Path?> {
        if (frozen) {
            if (scriptArg == "-") error("stdin mode does not support --frozen (no lockfile to bind to)")
            val path = resolveScript(scriptArg)
            val lockPath = Lockfile.pathFor(path)
            require(lockPath.exists()) {
                "frozen mode requires a lockfile, but none was found: $lockPath. Run `ktx lock $scriptArg` first."
            }
            return io.github.nebula.ktx.proto.v1.ResolverMode.FROZEN to lockPath
        }
        if (refreshLock) {
            if (scriptArg == "-") error("stdin mode does not support --lock")
            // In LOCK mode the daemon computes the lockfile path from the
            // script path itself, so the CLI does not need to send it (the
            // daemon-side LockingFlow.lockAndOptionallyRun uses
            // Lockfile.pathFor).
            return io.github.nebula.ktx.proto.v1.ResolverMode.LOCK to null
        }
        return io.github.nebula.ktx.proto.v1.ResolverMode.NORMAL to null
    }

    /**
     * Pick the JDK major version based on `@file:Toolchain(jdk = ...)`:
     *   - script doesn't declare -> current JVM major (no JDK switch; the
     *     daemon uses whatever JDK ktx itself runs on)
     *   - declared but matches current -> same as above
     *   - different version declared -> look up the installed JDK; install
     *     it if missing; use its java to launch the daemon
     *
     * Returns (major version, java executable path or null). null means
     * "use the current JVM".
     */
    private fun pickToolchain(): Pair<Int, String?> {
        val current = Runtime.version().feature()
        if (scriptArg == "-") return current to null  // no script to scan in stdin mode

        val path = java.nio.file.Path.of(scriptArg).takeIf { it.toFile().isFile } ?: return current to null
        val tc = ToolchainHeaderScanner.scan(path)
        val requested = tc.jdk.takeIf { it.isNotEmpty() }?.substringBefore('.')?.toIntOrNull()
            ?: return current to null
        if (requested == current) return current to null

        val store = ToolchainStore()
        val install = store.find(requested) ?: run {
            log.info("script requires JDK {}, not installed locally, downloading", requested)
            store.install(requested)
        }
        return requested to install.javaBin.toAbsolutePath().toString()
    }

    private fun runFile(runner: ScriptRunner): ResultWithDiagnostics<*> {
        val path = resolveScript(scriptArg)
        // If the script requires a different JDK, the line below re-execs
        // the current process and never returns. If no toolchain is
        // declared or it matches the current JVM, execution continues.
        ToolchainDispatcher.dispatchIfNeeded(path)
        preflightToolchain(path)
        val args = scriptArgs.toTypedArray()

        return when {
            frozen -> {
                val lockPath = Lockfile.pathFor(path)
                val lockfile = Lockfile.read(lockPath)
                    ?: error("frozen mode requires a lockfile, but none was found: $lockPath. Run `ktx lock $scriptArg` first.")
                log.info("frozen mode: using lockfile {} ({} direct deps)", lockPath.fileName, lockfile.directs.size)
                runner.run(path, args, resolver = FrozenResolver(lockfile))
            }
            refreshLock -> {
                LockingFlow.lockAndOptionallyRun(
                    runner = runner,
                    scriptPath = path,
                    scriptArgs = args,
                    skipExecution = false,
                )
            }
            else -> runner.run(path, args)
        }
    }

    private fun resolveScript(arg: String): Path {
        val path = Path(arg)
        require(path.exists() && path.isRegularFile()) { "script not found: $arg" }
        return path
    }

    /**
     * Phase 1.3: pre-flight check on the Kotlin version declared by the
     * toolchain annotation only. The `jdk` field is handled earlier by
     * [ToolchainDispatcher] (re-exec on mismatch).
     */
    private fun preflightToolchain(scriptPath: Path) {
        val tc = ToolchainHeaderScanner.scan(scriptPath)
        if (tc.kotlin.isNotEmpty() && tc.kotlin != BuildInfo.kotlinVersion) {
            throw IllegalStateException(
                "script declares Kotlin ${tc.kotlin} but the current CLI ships ${BuildInfo.kotlinVersion}; " +
                    "multi-Kotlin-version support is provided via daemon routing in Phase 2.",
            )
        }
    }
}
