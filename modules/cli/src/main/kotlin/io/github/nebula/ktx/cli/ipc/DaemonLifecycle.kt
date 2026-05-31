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
 * Detect / start the ktx daemon.
 *
 * Detection: try connecting to the socket. If it succeeds, the daemon is
 * alive; otherwise spawn a new daemon child process and wait for it to write
 * the socket file before connecting.
 *
 * Since Phase 2.2, daemons are routed by [jdkMajor]: each
 * (JDK major version, protocol version) tuple gets its own daemon directory
 * isolated from the others.
 *
 * Spawning a child process: reuse the current JVM's java executable and full
 * classpath, but switch the main class to
 * [io.github.nebula.ktx.daemon.Bootstrap]. The daemon directory is passed via
 * env var `KTX_DAEMON_DIR`.
 *
 * Concurrent-start guard: a `.start.lock` file in the daemon directory acts
 * as an advisory lock. When multiple CLIs ensureRunning at once, only one
 * actually forks; the others wait for the socket to appear.
 */
object DaemonLifecycle {

    private val log = LoggerFactory.getLogger("ktx.cli.daemon-lifecycle")
    private const val START_TIMEOUT_MS = 30_000L
    private const val POLL_INTERVAL_MS = 50L

    /**
     * Ensure that the daemon for [jdkMajor] is running and return a [DaemonClient].
     *
     * @param javaBin path to the java executable to use when forking the
     *   daemon. Defaults to the current JVM's; if the script declares
     *   `@file:Toolchain(jdk = "17")`, the caller should pass the path to
     *   the matching JDK's java so the daemon runs on that JDK.
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
            ?: error("cannot resolve current JVM path")

    private fun forkDaemon(daemonDir: Path, javaBin: String) {
        val classpath = System.getProperty("java.class.path")
            ?: error("java.class.path system property is empty")

        // The daemon's own AppCDS archive lives in the daemon directory. The
        // first JVM start auto-archives (AutoCreateSharedArchive); subsequent
        // starts hit the archive and shave ~1s off cold start. Note that .jsa
        // files are bound to (JDK class file version, classpath fingerprint),
        // and our daemon directory is already routed by jdkMajor so it is
        // never reused across instances, which is exactly what we want.
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
        error("daemon startup timed out (${START_TIMEOUT_MS / 1000}s). log: ${DaemonPaths.logFile(socketPath.parent)}")
    }

    fun pid(jdkMajor: Int): Long? = runCatching {
        DaemonPaths.pidFile(DaemonPaths.daemonDirFor(jdkMajor)).readText().trim().toLong()
    }.getOrNull()
}
