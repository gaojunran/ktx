package io.github.nebula.ktx.cli.ipc

import io.github.nebula.ktx.daemon.DaemonPaths
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * 检测 / 启动 ktx daemon。
 *
 * 检测策略：尝试连一下 socket。能连上就认为活着；连不上就启一个新 daemon
 * 子进程，等它写好 socket 文件后再连。
 *
 * 启动子进程：复用当前 JVM 的 java 可执行 + 完整 classpath，main class
 * 换成 [io.github.nebula.ktx.daemon.Bootstrap]。这样不需要单独打包 daemon
 * launcher 脚本 —— daemon 跟 CLI 共享一份 install。
 */
object DaemonLifecycle {

    private val log = LoggerFactory.getLogger("ktx.cli.daemon-lifecycle")
    private const val START_TIMEOUT_MS = 30_000L
    private const val POLL_INTERVAL_MS = 50L

    /**
     * 确保有一个 daemon 在跑并返回 [DaemonClient]。可能 fork 新进程。
     */
    fun ensureRunning(): DaemonClient {
        val socketPath = DaemonPaths.socketFile()
        if (socketAlive(socketPath)) {
            log.debug("daemon already running")
            return DaemonClient(socketPath)
        }
        log.info("starting daemon...")
        forkDaemon()
        waitForSocket(socketPath)
        return DaemonClient(socketPath)
    }

    /** 试连一下。能连上就关掉（仅做存活探测），不能连说明没人在 listen。 */
    private fun socketAlive(socketPath: Path): Boolean {
        if (!socketPath.exists()) return false
        return try {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { ch ->
                ch.connect(UnixDomainSocketAddress.of(socketPath))
                true
            }
        } catch (_: IOException) {
            // socket 文件残留但没人 listen（上次 daemon 崩溃留下的）
            false
        }
    }

    private fun forkDaemon() {
        val javaBin = ProcessHandle.current().info().command().orElse(null)
            ?: error("拿不到当前 JVM 路径")
        val classpath = System.getProperty("java.class.path")
            ?: error("java.class.path 系统属性为空")

        // 复用当前 JVM 的 -XX:SharedArchiveFile 等 JVM 参数？ —— 不传，让
        // daemon 用自己的（AutoCreateSharedArchive 会按需重建）。
        val cmd = listOf(
            javaBin,
            "-cp", classpath,
            "io.github.nebula.ktx.daemon.Bootstrap",
        )
        log.debug("fork daemon: {}", cmd.joinToString(" "))

        val pb = ProcessBuilder(cmd)
            .redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
        // detach：不让 CLI 退出后 daemon 也跟着死。Java 没有原生 setsid()，
        // 但 ProcessBuilder fork 出的进程不挂在 CLI 终端上 —— CLI 退出
        // 后 daemon 会变成 init 的孤儿继续运行。Phase 2.2 改用 nohup 模式
        // 进一步加固。
        pb.start()
    }

    private fun waitForSocket(socketPath: Path) {
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (socketAlive(socketPath)) {
                log.debug("daemon socket alive")
                return
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        error("daemon 启动超时（${START_TIMEOUT_MS / 1000}s）。日志：${DaemonPaths.logFile()}")
    }

    /** 试着读 daemon 的 PID（仅诊断用）。 */
    fun pid(): Long? = runCatching {
        DaemonPaths.pidFile().readText().trim().toLong()
    }.getOrNull()
}
