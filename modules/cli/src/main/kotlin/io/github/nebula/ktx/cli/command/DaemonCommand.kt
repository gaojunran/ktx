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
 * `ktx daemon` 子命令组。
 *
 * status / stop 默认作用在「当前 JVM 主版本对应的 daemon」上，可用 --jdk
 * 指定其他主版本。`stop --all` 关掉所有 daemon。
 */
class DaemonCommand : CliktCommand(name = "daemon") {
    override fun run() = Unit
}

class DaemonStatusCommand : CliktCommand(name = "status") {

    private val all by option("--all", help = "列出所有 daemon").flag()
    private val jdkOption by option("--jdk", help = "指定 JDK 主版本（默认当前）").int()

    override fun run() {
        if (all) {
            val root = DaemonPaths.defaultDaemonDir()
            if (!root.exists() || !root.isDirectory()) {
                echo("（无 daemon 目录）")
                return
            }
            val instances = root.listDirectoryEntries().filter { it.isDirectory() }
            if (instances.isEmpty()) {
                echo("（无 daemon 实例）")
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
            echo("daemon [$key]: 未运行（${daemonDir.fileName}）")
            return
        }
        val client = DaemonClient(DaemonPaths.socketFile(daemonDir))
        try {
            val s = client.status()
            echo("daemon [$key]: 运行中 (pid=$pid, dir=${daemonDir.fileName})")
            echo("  uptime          ${s.uptimeMs / 1000}s")
            echo("  scripts served  ${s.scriptsServed}")
            echo("  heap            ${s.heapUsedBytes / 1024 / 1024}M / ${s.heapMaxBytes / 1024 / 1024}M")
            echo("  ktx version     ${s.ktxVersion}")
        } catch (e: Exception) {
            echo("daemon [$key]: PID 文件存在但无法连接：${e.message}")
        }
    }
}

class DaemonStopCommand : CliktCommand(name = "stop") {

    private val all by option("--all", help = "关闭所有 daemon").flag()
    private val jdkOption by option("--jdk", help = "指定 JDK 主版本（默认当前）").int()

    override fun run() {
        if (all) {
            val root = DaemonPaths.defaultDaemonDir()
            if (!root.exists()) {
                echo("（无 daemon 目录）")
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
            echo("daemon [${daemonDir.fileName}]: 已请求关闭")
        } catch (e: Exception) {
            echo("daemon [${daemonDir.fileName}]: 关闭失败（可能已不在运行）：${e.message}")
        }
    }
}
