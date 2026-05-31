package io.github.nebula.ktx.core.compile

import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * 把脚本编译产物 jar + 一组运行时 jar 合并成一个 fat jar。
 *
 * 合并策略：
 *   - 普通类文件（`*.class`）：先到先得，重复 entry 跳过并 log warn。
 *     `inputs` 顺序决定优先级 —— 脚本产物放最前。
 *   - `META-INF/MANIFEST.MF`：丢弃所有源文件的 manifest，按 [mainClass] 重写。
 *   - `META-INF/services/...`：累加合并 —— 这是 ServiceLoader 约定，多个
 *     提供者要保留。同 service 文件出现多次时，把每份内容用换行拼接。
 *   - `module-info.class`：直接跳过。fat jar 走 classpath 路径，模块信息无意义
 *     且会因 split package 报错。
 *   - `META-INF/<vendor>/...` 等其他 metadata：先到先得。
 *   - 多 release jar 的 `META-INF/versions/N/...`：按普通文件先到先得处理；
 *     绝大多数库不用这个特性，需要时再做精细化。
 *
 * 不做的事：
 *   - 不 relocate（不像 shadow jar 那样重命名包）—— 这里所有依赖已经是
 *     embeddable 风味（用户自己声明的 maven 依赖正常包路径），不会和编译器
 *     冲突，因为编译器不在 fat jar 里。
 *   - 不压缩到极致（dexopt 之类）—— stdlib + 用户依赖已经是 deflate 过的 jar。
 */
class JarPacker(
    private val mainClass: String,
) {
    private val log = LoggerFactory.getLogger("ktx.compile.packer")

    fun pack(inputs: List<Path>, output: Path) {
        val seenEntries = mutableSetOf<String>()
        val services = mutableMapOf<String, StringBuilder>()

        Files.newOutputStream(output).buffered().use { fout ->
            JarOutputStream(fout, buildManifest()).use { jout ->
                for (input in inputs) {
                    JarFile(input.toFile()).use { jar ->
                        for (entry in jar.entries()) {
                            mergeEntry(entry, jar, jout, seenEntries, services)
                        }
                    }
                }
                // 收尾写出 services 累加结果
                writeServices(jout, services)
            }
        }
        log.info("打包完成：{}（{} entries）", output, seenEntries.size + services.size)
    }

    private fun buildManifest(): Manifest = Manifest().apply {
        mainAttributes.putValue("Manifest-Version", "1.0")
        mainAttributes.putValue("Main-Class", mainClass)
        mainAttributes.putValue("Created-By", "ktx-compile")
    }

    private fun mergeEntry(
        entry: JarEntry,
        jar: JarFile,
        jout: JarOutputStream,
        seenEntries: MutableSet<String>,
        services: MutableMap<String, StringBuilder>,
    ) {
        val name = entry.name
        when {
            entry.isDirectory -> Unit  // 跳过目录，writeJarEntry 会按需创建父目录
            name == "META-INF/MANIFEST.MF" -> Unit  // 丢弃，重写
            name == "module-info.class" -> Unit  // fat jar 不要模块描述
            name.startsWith("META-INF/versions/") && name.endsWith("/module-info.class") -> Unit
            name.startsWith("META-INF/services/") -> {
                val content = jar.getInputStream(entry).use { it.readBytes() }
                services.getOrPut(name) { StringBuilder() }
                    .appendLine(String(content, Charsets.UTF_8).trimEnd())
            }
            else -> {
                if (seenEntries.add(name)) {
                    jout.putNextEntry(JarEntry(name))
                    jar.getInputStream(entry).use { it.copyTo(jout) }
                    jout.closeEntry()
                }
                // 重复 entry 静默跳过 —— jar 库 split package 会很常见，
                // log 会刷屏。需要诊断时打开 DEBUG 级别。
            }
        }
    }

    private fun writeServices(jout: JarOutputStream, services: Map<String, StringBuilder>) {
        for ((name, content) in services) {
            jout.putNextEntry(JarEntry(name))
            jout.write(content.toString().toByteArray(Charsets.UTF_8))
            jout.closeEntry()
        }
    }

    companion object {
        /**
         * 直接拷贝整个 jar 内容到一个 [OutputStream]（用于「不需要合并、只需
         * 单个 jar 重写 manifest」场景）。当前未用，保留作未来扩展。
         */
        fun copyJarStripManifest(input: Path, output: OutputStream) {
            JarFile(input.toFile()).use { jar ->
                JarOutputStream(output, Manifest()).use { jout ->
                    for (entry in jar.entries()) {
                        if (entry.name == "META-INF/MANIFEST.MF") continue
                        jout.putNextEntry(JarEntry(entry.name))
                        if (!entry.isDirectory) {
                            jar.getInputStream(entry).use { it.copyTo(jout) }
                        }
                        jout.closeEntry()
                    }
                }
            }
        }
    }
}
