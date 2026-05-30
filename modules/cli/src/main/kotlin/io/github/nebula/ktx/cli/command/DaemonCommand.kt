package io.github.nebula.ktx.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import io.github.nebula.ktx.cli.ipc.DaemonClient
import io.github.nebula.ktx.cli.ipc.DaemonLifecycle
import io.github.nebula.ktx.daemon.DaemonPaths

/**
 * `ktx daemon` 子命令组。Phase 2.1 提供 status / shutdown 两个 —— 用户级别
 * 排错够用。stop --all、logs 等留到 Phase 2.2。
 */
class DaemonCommand : CliktCommand(name = "daemon") {
    override fun run() = Unit
}

class DaemonStatusCommand : CliktCommand(name = "status") {
    override fun run() {
        val pid = DaemonLifecycle.pid()
        if (pid == null) {
            echo("daemon: 未运行")
            return
        }
        val client = DaemonClient(DaemonPaths.socketFile())
        try {
            val s = client.status()
            echo("daemon: 运行中 (pid=$pid)")
            echo("  uptime          ${s.uptimeMs / 1000}s")
            echo("  scripts served  ${s.scriptsServed}")
            echo("  heap            ${s.heapUsedBytes / 1024 / 1024}M / ${s.heapMaxBytes / 1024 / 1024}M")
            echo("  ktx version     ${s.ktxVersion}")
        } catch (e: Exception) {
            echo("daemon: PID 文件存在但无法连接：${e.message}")
        }
    }
}

class DaemonStopCommand : CliktCommand(name = "stop") {
    override fun run() {
        val client = DaemonClient(DaemonPaths.socketFile())
        try {
            client.shutdown()
            echo("daemon: 已请求关闭")
        } catch (e: Exception) {
            echo("daemon: 关闭失败（可能已不在运行）：${e.message}")
        }
    }
}
