package io.github.nebula.ktx.daemon

import java.io.File
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Filesystem layout for the daemon.
 *
 * Since Phase 2.2, daemons are partitioned by [toolchainKey]: each
 * (jdkMajor, protocolVersion) tuple corresponds to an isolated daemon
 * instance.
 *
 * The directory looks like `~/.cache/ktx/d/<key12>/`, where `<key12>` is the
 * first 12 hex characters of the sha256, short enough to fit comfortably
 * within macOS's 104-byte sun_path limit. The full key is written to
 * `key.txt` for diagnostic lookup.
 *
 * Compatibility: [defaultDaemonDir] without a key argument still points to
 * the legacy `~/.cache/ktx/d/`, used by status/stop and similar commands
 * that have no specific script context (Phase 2.2 status by default only
 * inspects the current JVM's daemon; future versions may enumerate all).
 */
object DaemonPaths {

    private const val PROTOCOL_VERSION_TAG = "v1"

    /** Legacy directory (no routing); used by default for status/stop. */
    fun defaultDaemonDir(): Path {
        val xdg = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
        val base = if (xdg != null) File(xdg) else File(System.getProperty("user.home"), ".cache")
        return File(base, "ktx/d").toPath()
    }

    /**
     * Daemon directory for a given toolchain key. Phase 2.2: the key is
     * derived from jdkMajor + protocol version. Phase 2.3 also folds in the
     * kotlin version.
     */
    fun daemonDirFor(jdkMajor: Int): Path {
        val key = computeKey(jdkMajor)
        return defaultDaemonDir().resolve(key.short)
    }

    fun socketFile(daemonDir: Path = defaultDaemonDir()): Path = daemonDir.resolve("socket")
    fun pidFile(daemonDir: Path = defaultDaemonDir()): Path = daemonDir.resolve("pid")
    fun logFile(daemonDir: Path = defaultDaemonDir()): Path = daemonDir.resolve("log")
    fun keyFile(daemonDir: Path): Path = daemonDir.resolve("key.txt")

    fun computeKey(jdkMajor: Int): ToolchainKey {
        val raw = "jdk=$jdkMajor;protocol=$PROTOCOL_VERSION_TAG"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        val hex = digest.joinToString("") { "%02x".format(it) }
        return ToolchainKey(full = hex, raw = raw, short = hex.substring(0, 12))
    }

    data class ToolchainKey(
        val full: String,
        val raw: String,
        val short: String,
    )
}
