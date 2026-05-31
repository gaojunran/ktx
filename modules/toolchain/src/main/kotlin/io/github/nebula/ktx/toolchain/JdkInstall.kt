package io.github.nebula.ktx.toolchain

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isExecutable

/**
 * An installed JDK.
 *
 * Carries no version metadata — metadata is kept in the [ToolchainStore]
 * manifest to avoid parsing the `release` file on every instantiation.
 *
 * @property root JDK root directory (the top of the extracted archive).
 *   **Note** on macOS this is `<store>/<id>/`, with `Contents/Home/bin/java`
 *   nested inside.
 * @property platform the platform this JDK targets (used to derive the
 *   `bin/java` path).
 * @property majorVersion major version, e.g. 21 or 17.
 */
data class JdkInstall(
    val root: Path,
    val platform: Platform,
    val majorVersion: Int,
) {

    /** Absolute path of `bin/java`; cross-platform abstraction. */
    val javaBin: Path get() = root.resolve(platform.javaBinRelative())

    /** Where JAVA_HOME should point (`<root>/Contents/Home` on macOS). */
    val javaHome: Path get() = when (platform.os) {
        Platform.Os.MAC -> root.resolve("Contents/Home")
        else -> root
    }

    fun isUsable(): Boolean = javaBin.exists() && javaBin.isExecutable()
}
