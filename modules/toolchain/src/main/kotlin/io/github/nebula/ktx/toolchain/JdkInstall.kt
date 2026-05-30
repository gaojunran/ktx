package io.github.nebula.ktx.toolchain

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isExecutable

/**
 * 一份已安装的 JDK。
 *
 * 不持有版本元数据 —— 元数据由 [ToolchainStore] 维护在 manifest 里，避免每次
 * 实例化都去解析 `release` 文件。
 *
 * @property root JDK 根目录（解压后顶层）。**注意**在 macOS 上是
 *   `<store>/<id>/`，里面才是 `Contents/Home/bin/java`。
 * @property platform 这份 JDK 对应的平台（用于决定 `bin/java` 路径）。
 * @property majorVersion 主版本号，如 21、17。
 */
data class JdkInstall(
    val root: Path,
    val platform: Platform,
    val majorVersion: Int,
) {

    /** `bin/java` 绝对路径，跨平台抽象。 */
    val javaBin: Path get() = root.resolve(platform.javaBinRelative())

    /** JAVA_HOME 应当指向的目录（macOS 是 `<root>/Contents/Home`）。 */
    val javaHome: Path get() = when (platform.os) {
        Platform.Os.MAC -> root.resolve("Contents/Home")
        else -> root
    }

    fun isUsable(): Boolean = javaBin.exists() && javaBin.isExecutable()
}
