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
 *
 * Note: the daemon does NOT currently write logs to disk in production
 * because the cli module ships its own `logback.xml` on the shared
 * classpath, and main-kts bundles an `slf4j-simple` binding that wins the
 * SLF4J provider lottery for forked daemon processes. As a result
 * `~/.cache/ktx/d/<key12>/log` is typically empty. Fixing this requires
 * either repackaging main-kts to drop its slf4j-simple classes, or
 * isolating daemon classloading. Tracked in the Phase 3 TODO. Until then
 * `ktx daemon logs` prints what's there (plus a hint when empty).
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
