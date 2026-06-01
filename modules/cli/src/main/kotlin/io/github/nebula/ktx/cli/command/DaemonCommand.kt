package io.github.nebula.ktx.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.github.nebula.ktx.cli.ipc.DaemonClient
import io.github.nebula.ktx.cli.ipc.DaemonLifecycle
import io.github.nebula.ktx.daemon.DaemonPaths
import kotlin.io.path.bufferedReader
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

/**
 * `ktx daemon` subcommand group.
 *
 * status / stop default to the daemon for the current JVM major version;
 * use --jdk to target another major. `stop --all` stops every daemon.
 */
class DaemonCommand : CliktCommand(name = "daemon") {
    override fun help(context: Context): String = "Inspect and control the ktx daemon"
    override fun helpEpilog(context: Context): String = """
        The daemon keeps the Kotlin compiler warm across runs, reducing
        dev-loop time from ~750 ms to ~250 ms. It is auto-forked by
        `ktx run --daemon` when not already running.

        Subcommands default to the daemon for the current JVM major version;
        use --jdk to target another version, or --all for every daemon.
    """.trimIndent()
    override fun run() = Unit
}

class DaemonStatusCommand : CliktCommand(name = "status") {
    override fun help(context: Context): String = "Show daemon status (uptime, scripts served, heap usage)"

    private val all by option("--all", help = "List every daemon").flag()
    private val jdkOption by option("--jdk", help = "Target JDK major version (default: current)").int()

    override fun run() {
        if (all) {
            val root = DaemonPaths.defaultDaemonDir()
            if (!root.exists() || !root.isDirectory()) {
                echo("(no daemon directory)")
                return
            }
            val instances = root.listDirectoryEntries().filter { it.isDirectory() }
            if (instances.isEmpty()) {
                echo("(no daemon instances)")
                return
            }
            for (dir in instances) {
                printOne(dir)
                echo("")
            }
            return
        }
        val jdk = jdkOption ?: Runtime.version().feature()
        printOne(DaemonPaths.daemonDirFor(jdk))
    }

    private fun printOne(daemonDir: java.nio.file.Path) {
        val pidFile = DaemonPaths.pidFile(daemonDir)
        val keyFile = DaemonPaths.keyFile(daemonDir)
        val key = if (keyFile.exists()) keyFile.readText().trim() else "(unknown)"
        val pid = pidFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
        if (pid == null) {
            echo("daemon [$key]: not running (${daemonDir.fileName})")
            return
        }
        val client = DaemonClient(DaemonPaths.socketFile(daemonDir))
        try {
            val s = client.status()
            echo("daemon [$key]: running (pid=$pid, dir=${daemonDir.fileName})")
            echo("  uptime          ${s.uptimeMs / 1000}s")
            echo("  scripts served  ${s.scriptsServed}")
            echo("  heap            ${s.heapUsedBytes / 1024 / 1024}M / ${s.heapMaxBytes / 1024 / 1024}M")
            echo("  ktx version     ${s.ktxVersion}")
        } catch (e: Exception) {
            echo("daemon [$key]: pid file present but cannot connect: ${e.message}")
        }
    }
}

class DaemonStopCommand : CliktCommand(name = "stop") {
    override fun help(context: Context): String = "Stop a running daemon"

    private val all by option("--all", help = "Stop every daemon").flag()
    private val jdkOption by option("--jdk", help = "Target JDK major version (default: current)").int()

    override fun run() {
        if (all) {
            val root = DaemonPaths.defaultDaemonDir()
            if (!root.exists()) {
                echo("(no daemon directory)")
                return
            }
            for (dir in root.listDirectoryEntries().filter { it.isDirectory() }) {
                stopOne(dir)
            }
            return
        }
        val jdk = jdkOption ?: Runtime.version().feature()
        stopOne(DaemonPaths.daemonDirFor(jdk))
    }

    private fun stopOne(daemonDir: java.nio.file.Path) {
        val socketPath = DaemonPaths.socketFile(daemonDir)
        val client = DaemonClient(socketPath)
        try {
            client.shutdown()
            // The daemon's serve() loop exits, but the socket file removal,
            // executor shutdowns, and supervisor cleanup happen in finally
            // blocks afterwards. Wait until the socket file is actually gone
            // (or 5s elapse) so callers can safely re-start a daemon right
            // after `daemon stop` returns without racing the cleanup.
            waitForSocketRemoval(socketPath, timeoutMs = 5_000)
            echo("daemon [${daemonDir.fileName}]: stopped")
        } catch (e: Exception) {
            echo("daemon [${daemonDir.fileName}]: shutdown failed (already stopped?): ${e.message}")
        }
    }

    private fun waitForSocketRemoval(socketPath: java.nio.file.Path, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!socketPath.toFile().exists()) return
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
        // Fall through silently. The socket may still be there if cleanup
        // is unusually slow, but ensureRunning has its own retry so we don't
        // need to fail loudly here.
    }
}

/**
 * `ktx daemon logs [--all] [--jdk N] [--tail N | -n N] [--path]`
 *
 * Dump the daemon's log file. Each daemon writes to `<daemon-dir>/log` via
 * a logback FileAppender (no rotation today). To avoid pulling a huge log
 * into memory, we keep a rolling tail buffer of N lines.
 *
 * `--path` prints just the path, useful for piping into `tail -f` / `less`:
 *
 *     tail -f "$(ktx daemon logs --path)"
 */
class DaemonLogsCommand : CliktCommand(name = "logs") {
    override fun help(context: Context): String = "Dump the daemon's log file"
    override fun helpEpilog(context: Context): String = """
        Print just the path with --path, useful for piping:
          tail -f "$(ktx daemon logs --path)"
    """.trimIndent()

    private val all by option("--all", help = "Dump every daemon's log").flag()
    private val jdkOption by option("--jdk", help = "Target JDK major version (default: current)").int()
    private val tail by option(
        "--tail", "-n",
        help = "Number of trailing lines to print (default 100). Use 0 for the full log.",
    ).int().default(100)
    private val pathOnly by option(
        "--path",
        help = "Print just the log file path; do not dump contents.",
    ).flag()

    override fun run() {
        if (all) {
            val root = DaemonPaths.defaultDaemonDir()
            if (!root.exists() || !root.isDirectory()) {
                echo("(no daemon directory)")
                return
            }
            val instances = root.listDirectoryEntries().filter { it.isDirectory() }
            if (instances.isEmpty()) {
                echo("(no daemon instances)")
                return
            }
            instances.forEachIndexed { idx, dir ->
                if (idx > 0) echo("")
                printOne(dir)
            }
            return
        }
        val jdk = jdkOption ?: Runtime.version().feature()
        printOne(DaemonPaths.daemonDirFor(jdk))
    }

    private fun printOne(daemonDir: java.nio.file.Path) {
        val logFile = DaemonPaths.logFile(daemonDir)
        if (!logFile.exists()) {
            echo("daemon [${daemonDir.fileName}]: no log file (${logFile})")
            echo(LOGBACK_HINT)
            return
        }
        if (pathOnly) {
            echo(logFile.toString())
            return
        }
        echo("==> $logFile <==")
        if (logFile.toFile().length() == 0L) {
            echo("(log file is empty — the daemon's slf4j binding falls through to slf4j-simple from")
            echo(" the bundled main-kts jar, which writes to stderr instead of file. Tracked in TODO.)")
            return
        }
        if (tail <= 0) {
            // Full dump. Stream line by line so we don't load 10 MB into memory.
            logFile.bufferedReader().useLines { seq -> seq.forEach { echo(it) } }
            return
        }
        // Rolling tail buffer: O(N) memory regardless of file size.
        val deque = ArrayDeque<String>(tail)
        logFile.bufferedReader().useLines { seq ->
            for (line in seq) {
                if (deque.size == tail) deque.removeFirst()
                deque.addLast(line)
            }
        }
        deque.forEach { echo(it) }
    }

    companion object {
        private const val LOGBACK_HINT =
            "(log file is created lazily by logback FileAppender on the daemon's first " +
                "logger call; if the daemon hasn't started or hasn't logged anything yet, " +
                "this is expected.)"
    }
}
