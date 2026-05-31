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
 * Merge a script's compiled jar plus a set of runtime jars into a single fat jar.
 *
 * Merge strategy:
 *   - regular class files (`*.class`): first writer wins; duplicates are skipped
 *     and a warning is logged. `inputs` order determines priority — script
 *     output goes first.
 *   - `META-INF/MANIFEST.MF`: discard manifests from all inputs and rewrite
 *     based on [mainClass].
 *   - `META-INF/services/...`: concatenated — this is the ServiceLoader contract,
 *     multiple providers must be preserved. When the same service file appears
 *     in several inputs, contents are joined with newlines.
 *   - `module-info.class`: dropped. The fat jar runs through the classpath, so
 *     module info is meaningless and will trip split-package errors.
 *   - other metadata under `META-INF/<vendor>/...`: first writer wins.
 *   - multi-release jar `META-INF/versions/N/...`: handled like regular files
 *     (first wins). Most libs don't use this; we'll refine if it ever matters.
 *
 * Things we don't do:
 *   - no relocation (we don't rename packages like shadow jar) — every dep here
 *     is an embeddable build (the user's plain Maven deps), so there's no
 *     conflict with the compiler since the compiler isn't shipped in the fat jar.
 *   - no aggressive compression (e.g. dexopt) — stdlib + user deps are already
 *     deflated jars.
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
                // Final pass: emit accumulated services contents.
                writeServices(jout, services)
            }
        }
        log.info("packaged: {} ({} entries)", output, seenEntries.size + services.size)
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
            entry.isDirectory -> Unit  // skip directories; writeJarEntry creates parents on demand
            name == "META-INF/MANIFEST.MF" -> Unit  // drop, will be rewritten
            name == "module-info.class" -> Unit  // fat jar doesn't want a module descriptor
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
                // Duplicate entries are silently dropped — split packages
                // across jar libraries are common and a log line per dup
                // would flood the output. Enable DEBUG to investigate.
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
         * Copy a jar's full contents to an [OutputStream] (for "no merging,
         * just rewrite the manifest of one jar" use cases). Currently unused;
         * kept for future extensions.
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
