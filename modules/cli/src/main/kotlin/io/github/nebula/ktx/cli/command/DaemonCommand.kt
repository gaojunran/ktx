package io.github.nebula.ktx.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.github.nebula.ktx.cli.ipc.DaemonClient
import io.github.nebula.ktx.cli.ipc.DaemonLifecycle
import io.github.nebula.ktx.daemon.DaemonPaths
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
    override fun run() = Unit
}

class DaemonStatusCommand : CliktCommand(name = "status") {

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
        val client = DaemonClient(DaemonPaths.socketFile(daemonDir))
        try {
            client.shutdown()
            echo("daemon [${daemonDir.fileName}]: shutdown requested")
        } catch (e: Exception) {
            echo("daemon [${daemonDir.fileName}]: shutdown failed (already stopped?): ${e.message}")
        }
    }
}
