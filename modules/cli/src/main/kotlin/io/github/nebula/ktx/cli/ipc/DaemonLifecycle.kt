package io.github.nebula.ktx.cli.ipc

import io.github.nebula.ktx.daemon.DaemonPaths
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * 检测 / 启动 ktx daemon。
 *
 * 检测策略：尝试连一下 socket。能连上就认为活着；连不上就启一个新 daemon
 * 子进程，等它写好 socket 文件后再连。
 *
 * Phase 2.2 起按 [jdkMajor] 路由：每个 (JDK 主版本, 协议版本) 元组对应一个
 * 独立 daemon 目录，互不影响。
 *
 * 启动子进程：复用当前 JVM 的 java 可执行 + 完整 classpath，main class
 * 换成 [io.github.nebula.ktx.daemon.Bootstrap]。daemon 目录通过环境变量
 * `KTX_DAEMON_DIR` 传入。
 *
 * 并发启动保护：用 daemon 目录下的 `.start.lock` 文件做 advisory lock，
 * 多个 CLI 同时 ensureRunning 时只有一个真正 fork，其他人等 socket 出现。
 */
object DaemonLifecycle {

    private val log = LoggerFactory.getLogger("ktx.cli.daemon-lifecycle")
    private const val START_TIMEOUT_MS = 30_000L
    private const val POLL_INTERVAL_MS = 50L

    /**
     * 确保对应 [jdkMajor] 的 daemon 在跑并返回 [DaemonClient]。
     *
     * @param javaBin 用于 fork daemon 的 java 可执行文件路径。默认用当前
     *   JVM 的；如果脚本声明了 `@file:Toolchain(jdk = "17")`，调用方应当
     *   传入对应 JDK 的 java 路径，让 daemon 跑在那个 JDK 上。
     */
    fun ensureRunning(jdkMajor: Int, javaBin: String? = null): DaemonClient {
        val daemonDir = DaemonPaths.daemonDirFor(jdkMajor)
        daemonDir.createDirectories()
        val socketPath = DaemonPaths.socketFile(daemonDir)

        if (socketAlive(socketPath)) {
            log.debug("daemon already running for jdk={}", jdkMajor)
            return DaemonClient(socketPath)
        }

        val lockFile = daemonDir.resolve(".start.lock")
        RandomAccessFile(lockFile.toFile(), "rw").use { raf ->
            raf.channel.lock().use {
                if (!socketAlive(socketPath)) {
                    log.info("starting daemon for jdk={} ...", jdkMajor)
                    forkDaemon(daemonDir, javaBin ?: currentJavaBin())
                    waitForSocket(socketPath)
                }
            }
        }
        return DaemonClient(socketPath)
    }

    private fun socketAlive(socketPath: Path): Boolean {
        if (!socketPath.exists()) return false
        return try {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { ch ->
                ch.connect(UnixDomainSocketAddress.of(socketPath))
                true
            }
        } catch (_: IOException) {
            false
        }
    }

    private fun currentJavaBin(): String =
        ProcessHandle.current().info().command().orElse(null)
            ?: error("拿不到当前 JVM 路径")

    private fun forkDaemon(daemonDir: Path, javaBin: String) {
        val classpath = System.getProperty("java.class.path")
            ?: error("java.class.path 系统属性为空")

        // daemon 自己的 AppCDS 归档：放在 daemon 目录，第一次启动 JVM 自动
        // 归档（AutoCreateSharedArchive），后续启动命中省掉 ~1s 冷启。
        // 注意 jsa 是按 (JDK class file version, classpath fingerprint) 绑定的，
        // 我们 daemon 目录已经按 jdkMajor 路由，所以不会跨实例复用，刚好。
        val jsa = daemonDir.resolve("daemon.jsa").toAbsolutePath().toString()

        val cmd = listOf(
            javaBin,
            "-XX:SharedArchiveFile=$jsa",
            "-XX:+AutoCreateSharedArchive",
            "-cp", classpath,
            "io.github.nebula.ktx.daemon.Bootstrap",
        )
        log.debug("fork daemon: {} (KTX_DAEMON_DIR={})", cmd.joinToString(" "), daemonDir)

        val pb = ProcessBuilder(cmd)
            .redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
        pb.environment()["KTX_DAEMON_DIR"] = daemonDir.toAbsolutePath().toString()
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
        error("daemon 启动超时（${START_TIMEOUT_MS / 1000}s）。日志：${DaemonPaths.logFile(socketPath.parent)}")
    }

    fun pid(jdkMajor: Int): Long? = runCatching {
        DaemonPaths.pidFile(DaemonPaths.daemonDirFor(jdkMajor)).readText().trim().toLong()
    }.getOrNull()
}
