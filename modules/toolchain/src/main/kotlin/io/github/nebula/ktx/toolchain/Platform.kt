package io.github.nebula.ktx.toolchain

/**
 * 当前进程所在的 OS + CPU 架构，用 Adoptium API 的命名约定。
 *
 * Adoptium 的取值范围：
 *   - os: `mac` / `linux` / `windows` / `aix` / `solaris`（我们只支持前三）
 *   - architecture: `x64` / `aarch64` / `arm` / `ppc64` 等
 */
data class Platform(
    val os: Os,
    val arch: Arch,
) {

    enum class Os(val adoptium: String) {
        MAC("mac"),
        LINUX("linux"),
        WINDOWS("windows"),
    }

    enum class Arch(val adoptium: String) {
        X64("x64"),
        AARCH64("aarch64"),
    }

    /** 解压后 `bin/java` 的相对路径（macOS 多一层 Contents/Home）。 */
    fun javaBinRelative(): String = when (os) {
        Os.MAC -> "Contents/Home/bin/java"
        Os.WINDOWS -> "bin\\java.exe"
        Os.LINUX -> "bin/java"
    }

    /** 下载产物文件名后缀，用于决定解压方式。 */
    val archiveSuffix: String
        get() = if (os == Os.WINDOWS) ".zip" else ".tar.gz"

    companion object {
        /**
         * 检测当前 JVM 所在平台。基于 `os.name` / `os.arch` 系统属性，
         * 不依赖任何 native 调用。
         */
        fun current(): Platform {
            val osName = System.getProperty("os.name").lowercase()
            val osArch = System.getProperty("os.arch").lowercase()
            val os = when {
                osName.contains("mac") || osName.contains("darwin") -> Os.MAC
                osName.contains("win") -> Os.WINDOWS
                osName.contains("linux") -> Os.LINUX
                else -> error("不支持的 OS: $osName")
            }
            val arch = when (osArch) {
                "aarch64", "arm64" -> Arch.AARCH64
                "x86_64", "amd64", "x64" -> Arch.X64
                else -> error("不支持的 CPU 架构: $osArch")
            }
            return Platform(os, arch)
        }
    }
}
