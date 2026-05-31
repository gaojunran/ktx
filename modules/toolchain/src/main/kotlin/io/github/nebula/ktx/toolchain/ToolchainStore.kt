package io.github.nebula.ktx.toolchain

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.bufferedReader
import kotlin.io.path.bufferedWriter
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries

/**
 * Manages JDKs that ktx has downloaded itself: list, install, lookup by major version.
 *
 * Directory layout:
 * ```
 * $XDG_DATA_HOME/ktx/jdks/        (falls back to ~/.local/share/ktx/jdks/)
 *   temurin-21.0.11+10/           <- JDK root (macOS contains Contents/Home/ inside)
 *   temurin-17.0.13+11/
 *   manifest.tsv                  <- index of installed versions
 * ```
 *
 * `manifest.tsv` is the simplest TSV format (id\tmajor\tsemver), one JDK per line.
 * TSV over TOML because there's no nested structure here; parsing is 5 lines of code.
 */
class ToolchainStore(
    private val root: Path = defaultRoot(),
    private val platform: Platform = Platform.current(),
    private val client: AdoptiumClient = AdoptiumClient(),
) {

    private val log = LoggerFactory.getLogger("ktx.toolchain.store")

    init {
        root.createDirectories()
    }

    private val manifestFile: Path get() = root.resolve("manifest.tsv")

    /** Installed JDKs, sorted by major version ascending. */
    fun list(): List<Entry> {
        if (!manifestFile.exists()) return emptyList()
        return manifestFile.bufferedReader().useLines { lines ->
            lines.mapNotNull { parseManifestLine(it) }.sortedBy { it.major }.toList()
        }
    }

    /** Look up whether the given major version is already installed. */
    fun find(major: Int): JdkInstall? {
        val entry = list().firstOrNull { it.major == major } ?: return null
        val install = JdkInstall(
            root = root.resolve(entry.id),
            platform = platform,
            majorVersion = entry.major,
        )
        return install.takeIf { it.isUsable() }
    }

    /**
     * Install a JDK with major version = [major]; returns the existing one if
     * the same semver is already present.
     *
     * Flow:
     *   1. call Adoptium API for metadata;
     *   2. already installed → return immediately;
     *   3. download to a temp file, verify sha256;
     *   4. extract to `<store>/<id>/`;
     *   5. append a manifest line;
     *   6. verify `bin/java` exists and is executable.
     */
    fun install(major: Int): JdkInstall {
        val asset = client.resolveLatestGa(major, platform)
        val id = "temurin-${asset.semver}"

        val installDir = root.resolve(id)
        if (list().any { it.id == id } && installDir.exists()) {
            log.info("JDK already installed: {}", id)
            return JdkInstall(installDir, platform, major)
        }

        val archive = root.resolve("$id${platform.archiveSuffix}")
        log.info("downloading {} from Adoptium ({} MB)", asset.fileName, asset.size / 1024 / 1024)
        client.download(asset, archive)

        try {
            log.info("extracting to {}", installDir)
            installDir.createDirectories()
            Archive.extract(archive, installDir)
        } catch (e: Exception) {
            // Extraction failed; clean up the partial install
            installDir.toFile().deleteRecursively()
            throw e
        } finally {
            archive.deleteIfExists()
        }

        val install = JdkInstall(installDir, platform, major)
        require(install.isUsable()) {
            "${install.javaBin} is not executable after extraction. The archive may be corrupt or ktx's extractor has a bug."
        }
        appendManifest(Entry(id = id, major = major, semver = asset.semver))
        log.info("install complete: {} -> {}", id, install.javaHome)
        return install
    }

    /**
     * Append [entry] to the manifest (deduplicated). Creates the file if missing.
     */
    private fun appendManifest(entry: Entry) {
        val existing = if (manifestFile.exists()) list() else emptyList()
        if (existing.any { it.id == entry.id }) return
        val combined = existing + entry
        manifestFile.bufferedWriter().use { w ->
            for (e in combined) {
                w.write("${e.id}\t${e.major}\t${e.semver}")
                w.newLine()
            }
        }
    }

    private fun parseManifestLine(line: String): Entry? {
        val parts = line.split('\t')
        if (parts.size != 3) return null
        val major = parts[1].toIntOrNull() ?: return null
        return Entry(id = parts[0], major = major, semver = parts[2])
    }

    data class Entry(
        val id: String,
        val major: Int,
        val semver: String,
    )

    companion object {
        fun defaultRoot(): Path {
            val xdg = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
            val base = if (xdg != null) File(xdg) else File(System.getProperty("user.home"), ".local/share")
            return File(base, "ktx/jdks").toPath()
        }
    }
}
