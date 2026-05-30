package io.github.nebula.ktx.toolchain

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.io.IOException
import java.security.MessageDigest
import java.time.Duration
import kotlin.io.path.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.outputStream

/**
 * 调用 Adoptium API v3 解析「给定主版本 + 当前平台」对应的最新 GA JDK 二进制
 * 元数据（下载 URL、文件名、sha256），并下载到指定路径。
 *
 * 不做解压（解压由 [ToolchainStore] 调度）。这里只关心 HTTP 与校验和。
 *
 * API 文档：https://api.adoptium.net/q/swagger-ui/
 */
class AdoptiumClient(
    private val http: OkHttpClient = defaultClient(),
    private val moshi: Moshi = Moshi.Builder().build(),
) {

    private val log = LoggerFactory.getLogger("ktx.toolchain.adoptium")

    /**
     * 查询「主版本 [major] 在 [platform] 上的最新 GA JDK」。
     *
     * 返回值的 [Asset.downloadUrl] 是直链，[Asset.checksum] 是 sha256 hex。
     */
    fun resolveLatestGa(major: Int, platform: Platform): Asset {
        val url = "https://api.adoptium.net/v3/assets/feature_releases/$major/ga" +
            "?architecture=${platform.arch.adoptium}" +
            "&os=${platform.os.adoptium}" +
            "&image_type=jdk" +
            "&jvm_impl=hotspot" +
            "&heap_size=normal" +
            "&vendor=eclipse" +
            "&page_size=1"
        log.debug("GET {}", url)
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        http.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Adoptium API 返回 ${response.code}: ${response.message}" }
            val body = response.body?.string() ?: error("Adoptium API 响应体为空")
            return parseAsset(body, platform, major)
        }
    }

    /**
     * 下载 [asset.downloadUrl] 到 [destination]，过程中校验 sha256。
     * 失败时删除半成品，避免下次以为已下载。
     */
    fun download(asset: Asset, destination: java.nio.file.Path) {
        log.info("downloading {} -> {}", asset.fileName, destination)
        val request = Request.Builder().url(asset.downloadUrl).build()
        try {
            http.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "下载失败：${response.code} ${response.message}" }
                val body = response.body ?: error("下载响应体为空")
                val digest = MessageDigest.getInstance("SHA-256")
                destination.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            digest.update(buf, 0, n)
                            out.write(buf, 0, n)
                        }
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                require(actual.equals(asset.checksum, ignoreCase = true)) {
                    "sha256 校验失败：期望 ${asset.checksum}, 实际 $actual"
                }
            }
        } catch (e: Exception) {
            destination.deleteIfExists()
            throw e
        }
    }

    /**
     * 用最朴素的 JSON 路径解析，避免给一个一次性结构写完整 Moshi data class。
     * Adoptium 响应是一个数组，我们只取首元素的 `binaries[0].package`。
     */
    private fun parseAsset(json: String, platform: Platform, major: Int): Asset {
        @Suppress("UNCHECKED_CAST")
        val type = Types.newParameterizedType(List::class.java, Map::class.java)
        val adapter: JsonAdapter<List<Map<String, Any?>>> = moshi.adapter(type)
        val parsed = adapter.fromJson(json) ?: error("无法解析 Adoptium 响应")
        require(parsed.isNotEmpty()) { "Adoptium 没有 JDK $major 的 GA 版本（${platform.os.adoptium}/${platform.arch.adoptium}）" }
        val first = parsed[0]
        val binaries = first["binaries"] as? List<*>
            ?: error("响应缺少 binaries 字段")
        require(binaries.isNotEmpty()) { "binaries 数组为空" }
        @Suppress("UNCHECKED_CAST")
        val binary = binaries[0] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val pkg = binary["package"] as? Map<String, Any?>
            ?: error("binary 缺少 package 字段")
        val versionData = first["version_data"] as? Map<*, *>
        val semver = versionData?.get("semver") as? String ?: "unknown"
        return Asset(
            fileName = pkg["name"] as String,
            downloadUrl = pkg["link"] as String,
            checksum = pkg["checksum"] as String,
            size = (pkg["size"] as Number).toLong(),
            semver = semver,
            major = major,
        )
    }

    /**
     * 一份 JDK 二进制的元数据。
     */
    data class Asset(
        val fileName: String,
        val downloadUrl: String,
        val checksum: String,
        val size: Long,
        /** 形如 `21.0.11+10.0.LTS`。 */
        val semver: String,
        val major: Int,
    )

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(30))
            .readTimeout(Duration.ofMinutes(5))
            .writeTimeout(Duration.ofMinutes(5))
            .followRedirects(true)
            .build()
    }
}
