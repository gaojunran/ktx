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
 * Calls the Adoptium API v3 to resolve "latest GA JDK binary for the given
 * major version + current platform" metadata (download URL, filename, sha256),
 * then downloads it to a target path.
 *
 * Does not extract the archive (extraction is orchestrated by [ToolchainStore]).
 * This class only deals with HTTP and checksum verification.
 *
 * API docs: https://api.adoptium.net/q/swagger-ui/
 */
class AdoptiumClient(
    private val http: OkHttpClient = defaultClient(),
    private val moshi: Moshi = Moshi.Builder().build(),
) {

    private val log = LoggerFactory.getLogger("ktx.toolchain.adoptium")

    /**
     * Query "latest GA JDK for major version [major] on [platform]".
     *
     * The returned [Asset.downloadUrl] is a direct link, [Asset.checksum] is sha256 hex.
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
            require(response.isSuccessful) { "Adoptium API returned ${response.code}: ${response.message}" }
            val body = response.body?.string() ?: error("empty Adoptium API response body")
            return parseAsset(body, platform, major)
        }
    }

    /**
     * Download [asset.downloadUrl] to [destination], verifying sha256 along the way.
     * On failure, deletes the partial file so the next run doesn't think it's already downloaded.
     */
    fun download(asset: Asset, destination: java.nio.file.Path) {
        log.info("downloading {} -> {}", asset.fileName, destination)
        val request = Request.Builder().url(asset.downloadUrl).build()
        try {
            http.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "download failed: ${response.code} ${response.message}" }
                val body = response.body ?: error("empty download response body")
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
                    "sha256 mismatch: expected ${asset.checksum}, got $actual"
                }
            }
        } catch (e: Exception) {
            destination.deleteIfExists()
            throw e
        }
    }

    /**
     * Naive JSON path-style parsing — avoids writing a full Moshi data class
     * for a one-off shape. Adoptium's response is an array; we just take
     * `binaries[0].package` of the first element.
     */
    private fun parseAsset(json: String, platform: Platform, major: Int): Asset {
        @Suppress("UNCHECKED_CAST")
        val type = Types.newParameterizedType(List::class.java, Map::class.java)
        val adapter: JsonAdapter<List<Map<String, Any?>>> = moshi.adapter(type)
        val parsed = adapter.fromJson(json) ?: error("failed to parse Adoptium response")
        require(parsed.isNotEmpty()) { "Adoptium has no GA build for JDK $major (${platform.os.adoptium}/${platform.arch.adoptium})" }
        val first = parsed[0]
        val binaries = first["binaries"] as? List<*>
            ?: error("response missing binaries field")
        require(binaries.isNotEmpty()) { "binaries array is empty" }
        @Suppress("UNCHECKED_CAST")
        val binary = binaries[0] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val pkg = binary["package"] as? Map<String, Any?>
            ?: error("binary missing package field")
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
     * Metadata for a JDK binary.
     */
    data class Asset(
        val fileName: String,
        val downloadUrl: String,
        val checksum: String,
        val size: Long,
        /** Like `21.0.11+10.0.LTS`. */
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
