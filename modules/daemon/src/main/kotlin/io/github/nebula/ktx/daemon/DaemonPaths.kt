package io.github.nebula.ktx.daemon

import java.io.File
import java.nio.file.Path
import java.security.MessageDigest

/**
 * daemon 文件系统布局。
 *
 * Phase 2.2 起按 [toolchainKey] 分目录：每个 (jdkMajor, protocolVersion)
 * 元组对应一个独立 daemon 实例，互相隔离。
 *
 * 目录形如 `~/.cache/ktx/d/<key12>/`，其中 `<key12>` 是 sha256 前 12 位 hex
 * —— 短到不会撞 macOS sun_path 104 字节上限。完整 key 落在 `key.txt`，方便
 * 排错时反查。
 *
 * 兼容：[defaultDaemonDir] 不带 key 参数时仍指向旧的 `~/.cache/ktx/d/`，
 * 用于 status/stop 等没有具体脚本上下文的命令（Phase 2.2 status 默认只看
 * 当前 JVM 对应的 daemon，未来可扩展为枚举所有）。
 */
object DaemonPaths {

    private const val PROTOCOL_VERSION_TAG = "v1"

    /** 旧目录（无路由），用于 status/stop 默认行为。 */
    fun defaultDaemonDir(): Path {
        val xdg = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
        val base = if (xdg != null) File(xdg) else File(System.getProperty("user.home"), ".cache")
        return File(base, "ktx/d").toPath()
    }

    /**
     * 给定 toolchain key 的 daemon 目录。Phase 2.2：key 仅由 jdkMajor + 协议
     * 版本组成；Phase 2.3 加 kotlin 版本。
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
