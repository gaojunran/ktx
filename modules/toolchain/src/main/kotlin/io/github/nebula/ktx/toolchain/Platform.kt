package io.github.nebula.ktx.toolchain

/**
 * Current process's OS + CPU architecture, using Adoptium API naming.
 *
 * Adoptium's value space:
 *   - os: `mac` / `linux` / `windows` / `aix` / `solaris` (we only support the first three)
 *   - architecture: `x64` / `aarch64` / `arm` / `ppc64`, and so on
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

    /** Relative path to `bin/java` after extraction (macOS adds an extra Contents/Home layer). */
    fun javaBinRelative(): String = when (os) {
        Os.MAC -> "Contents/Home/bin/java"
        Os.WINDOWS -> "bin\\java.exe"
        Os.LINUX -> "bin/java"
    }

    /** Download artifact filename suffix; determines how to extract. */
    val archiveSuffix: String
        get() = if (os == Os.WINDOWS) ".zip" else ".tar.gz"

    companion object {
        /**
         * Detects the platform of the current JVM. Based on the `os.name` /
         * `os.arch` system properties, no native calls.
         */
        fun current(): Platform {
            val osName = System.getProperty("os.name").lowercase()
            val osArch = System.getProperty("os.arch").lowercase()
            val os = when {
                osName.contains("mac") || osName.contains("darwin") -> Os.MAC
                osName.contains("win") -> Os.WINDOWS
                osName.contains("linux") -> Os.LINUX
                else -> error("unsupported OS: $osName")
            }
            val arch = when (osArch) {
                "aarch64", "arm64" -> Arch.AARCH64
                "x86_64", "amd64", "x64" -> Arch.X64
                else -> error("unsupported CPU architecture: $osArch")
            }
            return Platform(os, arch)
        }
    }
}
