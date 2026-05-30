package io.github.nebula.ktx.daemon

import java.io.File
import java.nio.file.Path

/**
 * daemon 文件系统布局。Phase 2.1 单 daemon、单 socket 固定位置；Phase 2.2
 * 上 toolchain 路由后，会按 toolchainKey 分目录。
 *
 * macOS Unix domain socket 路径长度上限 104 字节（sun_path 字段）。我们在
 * cache dir 下用最短路径：`~/.cache/ktx/d/socket`。
 */
object DaemonPaths {

    /** 默认 daemon 的根目录（Phase 2.1 唯一一个）。 */
    fun defaultDaemonDir(): Path {
        val xdg = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
        val base = if (xdg != null) File(xdg) else File(System.getProperty("user.home"), ".cache")
        return File(base, "ktx/d").toPath()
    }

    fun socketFile(daemonDir: Path = defaultDaemonDir()): Path = daemonDir.resolve("socket")
    fun pidFile(daemonDir: Path = defaultDaemonDir()): Path = daemonDir.resolve("pid")
    fun logFile(daemonDir: Path = defaultDaemonDir()): Path = daemonDir.resolve("log")
}
