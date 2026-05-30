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
 * 管理 ktx 自己下载的 JDK：列举、安装、按主版本查找。
 *
 * 目录布局：
 * ```
 * $XDG_DATA_HOME/ktx/jdks/        （回退 ~/.local/share/ktx/jdks/）
 *   temurin-21.0.11+10/           ← JDK root（macOS 内含 Contents/Home/）
 *   temurin-17.0.13+11/
 *   manifest.tsv                  ← 已安装版本索引
 * ```
 *
 * `manifest.tsv` 用最简 TSV 格式（id\tmajor\tsemver），每行一个 JDK。
 * 选 TSV 而非 TOML 是因为这里完全不需要嵌套结构，TSV 解析 5 行代码搞定。
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

    /** 已安装的 JDK 列表，按主版本升序。 */
    fun list(): List<Entry> {
        if (!manifestFile.exists()) return emptyList()
        return manifestFile.bufferedReader().useLines { lines ->
            lines.mapNotNull { parseManifestLine(it) }.sortedBy { it.major }.toList()
        }
    }

    /** 查找指定主版本是否已安装。 */
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
     * 装一份「主版本 = [major]」的 JDK；如果已经装过相同 semver 直接返回。
     *
     * 流程：
     *   1. 调 Adoptium API 拿元数据；
     *   2. 已装 → 直接返回；
     *   3. 下载到临时文件，校验 sha256；
     *   4. 解压到 `<store>/<id>/`；
     *   5. 写 manifest 一行；
     *   6. 验证 `bin/java` 存在且可执行。
     */
    fun install(major: Int): JdkInstall {
        val asset = client.resolveLatestGa(major, platform)
        val id = "temurin-${asset.semver}"

        val installDir = root.resolve(id)
        if (list().any { it.id == id } && installDir.exists()) {
            log.info("JDK 已安装：{}", id)
            return JdkInstall(installDir, platform, major)
        }

        val archive = root.resolve("$id${platform.archiveSuffix}")
        log.info("从 Adoptium 下载 {} ({} MB)", asset.fileName, asset.size / 1024 / 1024)
        client.download(asset, archive)

        try {
            log.info("解压到 {}", installDir)
            installDir.createDirectories()
            Archive.extract(archive, installDir)
        } catch (e: Exception) {
            // 解压失败，清理半成品
            installDir.toFile().deleteRecursively()
            throw e
        } finally {
            archive.deleteIfExists()
        }

        val install = JdkInstall(installDir, platform, major)
        require(install.isUsable()) {
            "解压后 ${install.javaBin} 不可执行。归档可能损坏或 ktx 解压器有 bug。"
        }
        appendManifest(Entry(id = id, major = major, semver = asset.semver))
        log.info("安装完成：{} -> {}", id, install.javaHome)
        return install
    }

    /**
     * 把 [entry] 追加到 manifest（去重）。文件不存在则新建。
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
