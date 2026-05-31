package io.github.nebula.ktx.daemon

import java.nio.file.Path

/**
 * Daemon process entry point.
 *
 * When the CLI forks a daemon, it passes the daemon directory (the routed
 * `~/.cache/ktx/d/<key12>/`) via env var `KTX_DAEMON_DIR`. If unset, we fall
 * back to [DaemonPaths.defaultDaemonDir] (preserving Phase 2.1 behavior of
 * running `java ...Bootstrap` directly, e.g. for manual debugging).
 *
 * On startup we also set the system property `ktx.daemon.dir` so the logback
 * config's `${ktx.daemon.dir}/log` placeholder resolves, so each
 * daemon instance writes its own log file.
 */
object Bootstrap {
    @JvmStatic
    fun main(args: Array<String>) {
        val daemonDir = System.getenv("KTX_DAEMON_DIR")
            ?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it) }
            ?: DaemonPaths.defaultDaemonDir()
        System.setProperty("ktx.daemon.dir", daemonDir.toAbsolutePath().toString())
        DaemonServer(daemonDir).serve()
    }
}
