package io.github.nebula.ktx.core.compile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.writeText
import kotlin.script.experimental.api.ResultWithDiagnostics

/**
 * `ktx compile --native` flow.
 *
 * Wraps the fat jar produced by [CompileFlow] with GraalVM `native-image` to emit
 * a single self-contained binary (~10–20 MB) that starts in ~10–50 ms with no JRE
 * required.
 *
 * Targets the host platform only — `native-image` cannot cross-compile (same
 * constraint as `jlink` in [SelfContainedFlow]).
 *
 * ## Metadata layering
 *
 * GraalVM needs reflection / resource / proxy / serialization metadata at build
 * time. We hand `native-image` two configuration directories via
 * `-H:ConfigurationFileDirectories=<a>,<b>` and let it merge the entries:
 *
 *  1. **Build-time metaDir** (`<tmp>/meta/`) — populated with:
 *     - **ktx built-in base** (`ktx/native-image/{reflect,resource,...}-config.json`
 *       resources). Covers the Kotlin scripting host RunnerKt path: `KtsScript`,
 *       `KtsScriptDefinition`, `KtsEvaluationConfiguration`, `META-INF/services/...`,
 *       `kotlin_builtins`. Without these no script can boot.
 *     - **GraalVM Reachability Metadata Repository (GRMR)** subset bundled inside
 *       ktx itself (`ktx/grmr/<g>/<a>/<v>/reachability-metadata.json`). Covers
 *       mainstream user libraries (jackson three jars in MVP). Multiple GRMR
 *       files are JSON-array-concatenated into `metaDir/reachability-metadata.json`
 *       so native-image picks them up under one filename.
 *  2. **Per-script metaDir** at `<script>.native-meta/` — populated by
 *     `--collect-metadata` running `native-image-agent` against the fat jar.
 *     Lets scripts whose deps aren't in GRMR collect their own reflection trace
 *     once and re-use it on subsequent builds.
 *
 * ## `--no-fallback` is mandatory
 *
 * We always pass `--no-fallback`, so if any reflection/resource is missing the
 * build fails loudly rather than emitting a fat fallback image. Forces problems
 * to surface at compile time.
 */
class NativeImageFlow {

    private val log = LoggerFactory.getLogger("ktx.compile.native")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /**
     * @param scriptPath path to the `.kts` / `.main.kts` source.
     * @param outputBin path of the binary to produce. The caller is responsible
     *   for the `.exe` suffix on Windows; here we only enforce that
     *   `native-image`'s own output (which always ends `.exe` on Windows
     *   regardless of `-H:Name`) is moved to [outputBin] at the end.
     * @param collectMetadata when true, run the fat jar once on the JVM with
     *   `native-image-agent` first to capture reflection/resource accesses. The
     *   captured metadata is written to `<script>.native-meta/` for re-use.
     * @param extraNativeImageArgs raw args appended after ktx's own flags.
     */
    fun produce(
        scriptPath: Path,
        outputBin: Path,
        collectMetadata: Boolean,
        extraNativeImageArgs: List<String> = emptyList(),
    ): ResultWithDiagnostics<*> {
        val nativeImage = locateNativeImage()
            ?: error(
                "`native-image` not found on PATH or under \$GRAALVM_HOME / \$JAVA_HOME.\n" +
                    "Install GraalVM, e.g.:\n" +
                    "  mise use -g aqua:oracle/graalvm@21\n" +
                    "Then re-run this command.",
            )
        log.info("native-image: {}", nativeImage)

        val absScript = scriptPath.absolute().normalize()
        val tmpDir = Files.createTempDirectory("ktx-native-")

        try {
            // Step 1: produce fat jar via the existing CompileFlow.
            val fatJar = tmpDir.resolve("app.jar")
            val (compileResult, metadata) = CompileFlow().compileWithMetadata(absScript, fatJar)
            if (compileResult is ResultWithDiagnostics.Failure || metadata == null) {
                return compileResult
            }
            log.info("fat jar built: {} ({} KB)", fatJar, fatJar.fileSize() / 1024)

            // Rewrite the manifest's Main-Class to ktx's NativeBootstrap shim,
            // saving the original (script-generated) main class under a
            // custom attribute. This is the workaround for the
            // JvmScriptingHostConfigurationKt.<clinit> NPE in native binaries:
            // the shim sets `java.home` etc. before the scripting host's
            // static initializers fire.
            rewriteFatJarMainClass(fatJar, metadata.mainClass)

            // Step 2: assemble the build-time metaDir with ktx built-ins,
            // and a separate grmrDir holding META-INF/native-image/<g>/<a>/
            // entries for matched GraalVM Reachability Metadata Repository
            // coordinates.
            val metaDir = tmpDir.resolve("meta").also { it.createDirectories() }
            val grmrDir = tmpDir.resolve("grmr").also { it.createDirectories() }
            copyBuiltinMetadata(metaDir)
            val grmrHasContent = copyGrmrMetadata(metadata.resolvedCoords, grmrDir)
            // Also register the script's auto-generated main class for
            // reflective dispatch from NativeBootstrap. The class name is
            // per-script (e.g. `Hello_main`, `Jackson_main`) so we can't bake
            // it into the static reflect-config.json shipped in resources.
            writeScriptMainReflectConfig(metaDir, metadata.mainClass)

            // Step 3: optionally run the agent so per-script metadata is up to date.
            val scriptMetaDir = scriptMetaDirFor(absScript)
            if (collectMetadata) {
                scriptMetaDir.createDirectories()
                runAgent(nativeImage, fatJar, scriptMetaDir)
                log.info("script metadata persisted to {}", scriptMetaDir)
            }

            // Step 4: hand both dirs to native-image. It does dedup on its end.
            val configDirs = buildList {
                add(metaDir)
                if (scriptMetaDir.exists() && scriptMetaDir.isDirectory()) add(scriptMetaDir)
            }
            outputBin.parent?.createDirectories()
            runNativeImage(
                nativeImage,
                fatJar,
                configDirs,
                grmrDir = if (grmrHasContent) grmrDir else null,
                outputBin = outputBin,
                extraArgs = extraNativeImageArgs,
            )

            log.info("native binary ready: {} ({} KB)", outputBin, outputBin.fileSize() / 1024)
            return compileResult
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    // ----------------------------------------------------------------------
    // Tool location
    // ----------------------------------------------------------------------

    /**
     * Look for `native-image` in this order:
     *  1. `$GRAALVM_HOME/bin`
     *  2. `$JAVA_HOME/bin` — common when ktx itself runs on a GraalVM
     *  3. anywhere on `PATH`
     */
    internal fun locateNativeImage(): Path? {
        val exe = if (isWindows) "native-image.cmd" else "native-image"
        val candidates = sequence {
            System.getenv("GRAALVM_HOME")?.takeIf { it.isNotBlank() }?.let { yield(Path.of(it, "bin", exe)) }
            System.getenv("JAVA_HOME")?.takeIf { it.isNotBlank() }?.let { yield(Path.of(it, "bin", exe)) }
            System.getenv("PATH")?.split(java.io.File.pathSeparator)?.forEach {
                yield(Path.of(it, exe))
            }
        }
        return candidates.firstOrNull { it.exists() }
    }

    // ----------------------------------------------------------------------
    // Metadata gathering
    // ----------------------------------------------------------------------

    /**
     * Copy `ktx/native-image/{reflect,resource,serialization,proxy,jni}-config.json`
     * out of the ktx-core jar resources into [metaDir].
     */
    private fun copyBuiltinMetadata(metaDir: Path) {
        for (name in BUILTIN_METADATA_FILES) {
            val resource = "ktx/native-image/$name"
            val stream = NativeImageFlow::class.java.classLoader.getResourceAsStream(resource)
                ?: error("missing built-in metadata resource: $resource")
            val target = metaDir.resolve(name)
            stream.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
        }
        log.debug("built-in metadata copied to {}", metaDir)
    }

    /**
     * For each top-level coordinate "g:a:v" produced by [RecordingResolver], try
     * to find a bundled GRMR entry under
     * `ktx/grmr/<g>/<a>/<v>/reachability-metadata.json`. If the exact version
     * is unavailable, fall back via a `versions.txt` index file. Missing
     * libraries are logged at INFO; not an error.
     *
     * Each matched file is dropped under
     * `<grmrDir>/META-INF/native-image/<groupId>/<artifactId>/reachability-metadata.json`.
     * The caller adds `<grmrDir>` to the native-image classpath so GraalVM's
     * standard discovery (scanning the classpath for
     * `META-INF/native-image/<g>/<a>/reachability-metadata.json` entries)
     * picks them up automatically. We can't merge multiple GRMR objects into one file because GraalVM expects this filename to contain
     * a single unified-format object, not a JSON array.
     */
    private fun copyGrmrMetadata(coords: List<String>, grmrDir: Path): Boolean {
        var any = false
        for (coord in coords) {
            val (group, artifact, version) = parseGav(coord) ?: continue
            val resourcePath = findGrmrResource(group, artifact, version)
            if (resourcePath == null) {
                log.info("grmr: no bundled metadata for {}", coord)
                continue
            }
            log.info("grmr: bundled metadata for {} -> {}", coord, resourcePath)
            val stream = NativeImageFlow::class.java.classLoader.getResourceAsStream(resourcePath)
                ?: continue
            val targetDir = grmrDir.resolve("META-INF/native-image/$group/$artifact").also { it.createDirectories() }
            val target = targetDir.resolve("reachability-metadata.json")
            stream.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
            any = true
        }
        return any
    }

    private fun findGrmrResource(group: String, artifact: String, version: String): String? {
        val cl = NativeImageFlow::class.java.classLoader
        val exact = "ktx/grmr/$group/$artifact/$version/reachability-metadata.json"
        if (cl.getResource(exact) != null) return exact
        val indexResource = "ktx/grmr/$group/$artifact/versions.txt"
        val indexStream = cl.getResourceAsStream(indexResource) ?: return null
        val versions = indexStream.bufferedReader().readLines().filter { it.isNotBlank() }
        if (versions.isEmpty()) return null
        // Sort versions lexicographically and take the largest. Sufficient for
        // semver-shaped jackson; if a library uses unusual versioning we'll
        // refine the comparator.
        val pick = versions.sorted().last()
        return "ktx/grmr/$group/$artifact/$pick/reachability-metadata.json"
    }

    private fun parseGav(coord: String): Triple<String, String, String>? {
        val parts = coord.split(":")
        if (parts.size < 3) return null
        return Triple(parts[0], parts[1], parts[2])
    }

    // ----------------------------------------------------------------------
    // native-image-agent (--collect-metadata)
    // ----------------------------------------------------------------------

    /**
     * Run the fat jar once on the JVM with `native-image-agent` so it records
     * reflection / resource accesses. Output goes directly to [scriptMetaDir]
     * via `config-merge-dir` so existing entries (from a previous collect run)
     * are preserved and extended.
     *
     * The agent is shipped with GraalVM and auto-loaded via the `-agentlib:`
     * option; no separate install step.
     */
    private fun runAgent(nativeImage: Path, fatJar: Path, scriptMetaDir: Path) {
        val javaBin = nativeImage.parent?.resolve(if (isWindows) "java.exe" else "java")
            ?: error("could not infer java binary location next to $nativeImage")
        require(javaBin.exists()) { "java binary not found next to native-image: $javaBin" }
        log.info("running fat jar under native-image-agent (this executes the script body)...")
        val cmd = listOf(
            javaBin.toString(),
            "-agentlib:native-image-agent=config-merge-dir=$scriptMetaDir," +
                "config-write-period-secs=10,config-write-initial-delay-secs=1",
            "-jar", fatJar.toString(),
        )
        log.debug("exec: {}", cmd.joinToString(" "))
        val proc = ProcessBuilder(cmd).inheritIO().start()
        val exit = proc.waitFor()
        if (exit != 0) {
            error("agent run failed (exit=$exit). Run `java -jar $fatJar` manually to debug.")
        }
    }

    // ----------------------------------------------------------------------
    // native-image invocation
    // ----------------------------------------------------------------------

    private fun runNativeImage(
        nativeImage: Path,
        fatJar: Path,
        configDirs: List<Path>,
        grmrDir: Path?,
        outputBin: Path,
        extraArgs: List<String>,
    ) {
        val outDir = outputBin.parent ?: Path.of(".")
        val outName = outputBin.fileName.toString().removeSuffix(".exe")
        val args = buildList {
            add("--no-fallback")
            // Optimisation/footprint flags. -O2 is the default but explicit
            // for log clarity. --gc=serial is the only GC available on GraalVM
            // CE and matches our short-lived script profile. -march=compatibility
            // lets the binary run on older CPUs (default x86-64-v3 SIGILLs on
            // pre-2017 Intel).
            //
            // -Os is intentionally absent: it became a public flag only in
            // GraalVM 24+, and ktx targets 21+ for now.
            add("-O2")
            add("--gc=serial")
            add("-march=compatibility")
            // ConfigurationFileDirectories scans each given dir for
            // {reflect,resource,proxy,serialization,jni}-config.json AND
            // reachability-metadata.json. Multiple dirs are comma-separated
            // and GraalVM dedups on its end. Experimental in 21; the unlock
            // is a no-op on later versions.
            add("-H:+UnlockExperimentalVMOptions")
            add("-H:ConfigurationFileDirectories=${configDirs.joinToString(",")}")
            // GRMR metadata uses the unified `reachability-metadata.json`
            // schema and is consumed via the standard classpath-discovery
            // mechanism (`META-INF/native-image/<g>/<a>/reachability-metadata.json`).
            // Adding the grmrDir to the classpath lets GraalVM auto-discover
            // and dedup the bundled entries.
            if (grmrDir != null) {
                add("-cp")
                add(grmrDir.toString())
            }
            // Class init policy.
            //
            // We deliberately do NOT pass --initialize-at-build-time=kotlin,kotlinx.
            // Mixing a sweeping build-time directive with run-time directives
            // triggers a "class X was requested to be initialized at run time
            // but got initialized at build time" cascade for kotlin-reflect
            // internals and main-kts's transitive Aether classes.
            //
            // The well-known JvmScriptingHostConfigurationKt.<clinit> NPE is
            // worked around by [rewriteFatJarMainClass] which substitutes the
            // jar's Main-Class with [io.github.nebula.ktx.core.bootstrap.NativeBootstrap].
            // That shim sets sane defaults for `java.home` / `user.home` BEFORE
            // the Kotlin scripting host's static initializers run, then loads
            // the original (compiled-script) main class via a resource named
            // `ktx/script-main.txt` (also injected by the rewrite step).
            addAll(extraArgs)
            add("-jar")
            add(fatJar.toString())
            // -o must come after -jar in GraalVM 21: when -jar is present
            // GraalVM derives -H:Name from the jar's filename and -H:Name
            // is silently ignored. -o overrides it correctly.
            add("-o")
            add("$outDir/$outName")
        }
        log.info("native-image build (this takes 30-180s)...")
        log.debug("exec: {} {}", nativeImage, args.joinToString(" "))
        val pb = ProcessBuilder(listOf(nativeImage.toString()) + args).inheritIO()
        val proc = pb.start()
        val exit = proc.waitFor()
        if (exit != 0) {
            error(
                "native-image failed (exit=$exit). Common causes:\n" +
                    "  - missing reflection metadata: re-run with --collect-metadata\n" +
                    "  - class init order issue: pass extra `--initialize-at-run-time=<pkg>`\n" +
                    "  - native-image build OOM: bump --max-heap-size",
            )
        }
        // native-image emits ${outDir}/${outName} (Linux/macOS) or
        // ${outDir}/${outName}.exe (Windows). Move to the user-requested path
        // if they differ.
        val produced = outDir.resolve(if (isWindows) "$outName.exe" else outName)
        if (produced != outputBin && produced.exists()) {
            Files.move(produced, outputBin, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun scriptMetaDirFor(scriptPath: Path): Path =
        scriptPath.resolveSibling(scriptPath.fileName.toString() + ".native-meta")

    /**
     * Append a reflect-config entry registering the script's auto-generated
     * main class so [io.github.nebula.ktx.core.bootstrap.NativeBootstrap]
     * can resolve and call its `main([Ljava/lang/String;)V` reflectively.
     *
     * We don't try to merge with [metaDir]'s existing reflect-config.json
     * (copied from ktx built-ins) — instead we drop a separate file under a
     * dedicated subdirectory and pass it as a second config dir to
     * native-image. GraalVM unions metadata across dirs and dedupes by class
     * name, so the result is equivalent to a manual merge but a lot less
     * code.
     */
    private fun writeScriptMainReflectConfig(metaDir: Path, scriptMainClass: String) {
        val entry = """
            [
              {
                "name": "$scriptMainClass",
                "methods": [{ "name": "main", "parameterTypes": ["java.lang.String[]"] }]
              }
            ]
        """.trimIndent()
        // We append to the existing reflect-config.json by re-parsing and
        // joining at the JSON level. Cheap and avoids the double-config-dir
        // shuffle.
        val existingFile = metaDir.resolve("reflect-config.json")
        val existing = json.parseToJsonElement(existingFile.toFile().readText())
        val newEntry = json.parseToJsonElement(entry)
        require(existing is JsonArray && newEntry is JsonArray) {
            "reflect-config.json should be a JSON array, got ${existing::class.simpleName}"
        }
        val merged = JsonArray(existing.toList() + newEntry.toList())
        existingFile.toFile().writeText(json.encodeToString(JsonArray.serializer(), merged))
    }

    /**
     * Replace the fat jar's `Main-Class` with ktx's `NativeBootstrap` shim and
     * inject a resource (`ktx/script-main.txt`) holding the original main
     * class name so the shim can dispatch to it. We do this by streaming the
     * input jar to a sibling temp jar, then atomically replacing the original.
     */
    private fun rewriteFatJarMainClass(fatJar: Path, originalMainClass: String) {
        val tmp = fatJar.resolveSibling(fatJar.fileName.toString() + ".tmp")
        java.util.jar.JarFile(fatJar.toFile()).use { jin ->
            val manifest = jin.manifest ?: java.util.jar.Manifest()
            manifest.mainAttributes.putValue("Main-Class", "io.github.nebula.ktx.core.bootstrap.NativeBootstrap")
            Files.newOutputStream(tmp).buffered().use { out ->
                java.util.jar.JarOutputStream(out, manifest).use { jout ->
                    val entries = jin.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.name == "META-INF/MANIFEST.MF") continue
                        if (entry.name == "ktx/script-main.txt") continue  // overwrite with current value
                        jout.putNextEntry(java.util.jar.JarEntry(entry.name))
                        if (!entry.isDirectory) {
                            jin.getInputStream(entry).use { it.copyTo(jout) }
                        }
                        jout.closeEntry()
                    }
                    // Inject the script-main marker resource. NativeBootstrap
                    // reads this at runtime to find the script's entry class.
                    jout.putNextEntry(java.util.jar.JarEntry("ktx/script-main.txt"))
                    jout.write(originalMainClass.toByteArray(Charsets.UTF_8))
                    jout.closeEntry()
                }
            }
        }
        Files.move(tmp, fatJar, StandardCopyOption.REPLACE_EXISTING)
        log.debug("fat jar rewritten: Main-Class -> NativeBootstrap, ktx/script-main.txt={}", originalMainClass)
    }

    companion object {
        private val isWindows: Boolean
            get() = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

        private val BUILTIN_METADATA_FILES = listOf(
            "reflect-config.json",
            "resource-config.json",
            "serialization-config.json",
            "proxy-config.json",
            "jni-config.json",
        )
    }
}
