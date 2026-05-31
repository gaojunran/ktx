package io.github.nebula.ktx.core.compile

import io.github.nebula.ktx.core.deps.LockingFlow
import io.github.nebula.ktx.core.deps.RecordingResolver
import io.github.nebula.ktx.core.exec.ScriptRunner
import io.github.nebula.ktx.core.script.KtsScript
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.jvm.util.KotlinJars

/**
 * Per-compilation byproducts that downstream flows ([SelfContainedFlow], [NativeImageFlow])
 * can consume without re-running the resolver.
 *
 * - [fatJar] is the produced output jar (same path as the caller's `outputJar`).
 * - [resolvedCoords] are the top-level Maven coordinates the script's `@file:DependsOn`
 *   asked for, in the form `g:a:v`. NativeImageFlow uses these to look up bundled
 *   GraalVM Reachability Metadata Repository (GRMR) entries.
 * - [mainClass] is the auto-generated `Main-Class` attribute baked into the fat jar
 *   (e.g. `Hello_main`). Native-image can't read it from the manifest because we
 *   pass the jar via `-jar`, so callers may need it explicitly.
 */
data class CompileMetadata(
    val fatJar: Path,
    val resolvedCoords: List<String>,
    val mainClass: String,
)

/**
 * Compile a .kts script into a self-contained fat jar that depends on neither
 * ktx nor the Kotlin compiler.
 *
 * Workflow:
 *   1. Wrap the real resolver in a [RecordingResolver] and **compile only**
 *      (don't execute) the script, capturing every dep jar path main-kts resolves.
 *   2. Compiled artifacts land in a dedicated build cache directory (we
 *      temporarily redirect the system property
 *      "kotlin.main.kts.compiled.scripts.cache.dir" so main-kts writes there).
 *   3. [JarPacker] merges the compiled jar + dep jars + the runtime classes ktx
 *      needs into a single fat jar.
 *
 * The output jar does NOT contain:
 *   - kotlin-compiler-embeddable (script is already compiled)
 *   - kotlin-scripting-* (no scripting host needed at runtime)
 *   - main-kts.jar (no MainKtsConfigurator needed at runtime)
 *   - ktx's own cli/daemon/protocol modules (irrelevant at runtime)
 *
 * The output jar DOES contain:
 *   - kotlin-stdlib + kotlin-script-runtime
 *   - every jar resolved from @file:DependsOn
 *   - the ktx core module jar (provides the KtsScript base class)
 */
class CompileFlow {

    private val log = LoggerFactory.getLogger("ktx.compile")

    /**
     * Convenience overload for callers that only need the diagnostics result
     * (the original public API; CLI's `runFatJar` flow goes through here).
     */
    fun compile(scriptPath: Path, outputJar: Path): ResultWithDiagnostics<*> =
        compileWithMetadata(scriptPath, outputJar).first

    /**
     * Same as [compile], but additionally returns the [CompileMetadata] needed by
     * native-image / GRMR matching. The metadata is null if compilation failed.
     */
    fun compileWithMetadata(
        scriptPath: Path,
        outputJar: Path,
    ): Pair<ResultWithDiagnostics<*>, CompileMetadata?> {
        val absScriptPath = scriptPath.absolute().normalize()
        val tmpCacheDir = Files.createTempDirectory("ktx-compile-cache-")

        try {
            // Redirect main-kts's cache dir to our temp location — the
            // compiled artifact lands there so we can locate it precisely
            // (the global cache contains many jars and is hard to filter).
            val originalCache = System.getProperty(MAIN_KTS_CACHE_DIR_PROPERTY)
            System.setProperty(MAIN_KTS_CACHE_DIR_PROPERTY, tmpCacheDir.toAbsolutePath().toString())

            val runner = ScriptRunner(cacheDir = tmpCacheDir)
            val recording = RecordingResolver(LockingFlow.defaultRealResolver())

            log.info("compiling {}", absScriptPath)

            // Important: we do **not** use wrapForLockOnly. The lock flow
            // replaces the body with Unit to avoid side effects, but compile
            // must keep the body intact — the user expects the produced jar
            // to actually run their code. So we go through runner.run (file
            // path) and let main-kts compile the full source and emit a jar.
            //
            // Side-effect note: the body does get executed once (HTTP requests
            // and so on). That's no different from the first `ktx run`. The
            // cost is acceptable. Phase 2.5 may add an "only compile, never
            // evaluate" entry point (main-kts's BasicJvmScriptingHost has a
            // suitable API internally, but bypassing RunnerKt's default
            // evaluator takes effort).
            val compileResult = runner.run(absScriptPath, emptyArray(), resolver = recording)

            if (originalCache != null) System.setProperty(MAIN_KTS_CACHE_DIR_PROPERTY, originalCache)
            else System.clearProperty(MAIN_KTS_CACHE_DIR_PROPERTY)

            if (compileResult is ResultWithDiagnostics.Failure) {
                return compileResult to null
            }

            // Locate the freshly produced jar in tmpCacheDir. There may be 0 or 1:
            //   0: the script is too trivial. main-kts's caching for inline
            //      scripts doesn't always write to disk (the cache hit
            //      condition involves "non-transient ScriptCompilationConfiguration
            //      hash" + "script source"; in inline mode source is fine,
            //      but in some scenarios the computed cache key isn't persisted).
            // For the 0-file case we'd need a fallback that runs the wrapped
            // source through the full compile pipeline.
            val jars = tmpCacheDir.toFile().listFiles { _, n -> n.endsWith(".jar") }.orEmpty()
            require(jars.size == 1) {
                "unexpected number of compiled jars (cacheDir=$tmpCacheDir, found=${jars.size}). " +
                    "This usually means main-kts's cache key algorithm has changed and ktx needs updating."
            }
            val compiledJar = jars[0]

            val resolvedDeps = recording.snapshot().values.flatten().distinct().map { it.toPath() }
            val runtimeJars = collectRuntimeJars()
            val ktxCoreJar = locateKtxCoreJar()

            log.info(
                "merging: script artifact ({}KB) + {} user deps + {} Kotlin runtime jars{}",
                compiledJar.length() / 1024,
                resolvedDeps.size,
                runtimeJars.size,
                if (ktxCoreJar != null) " + ktx core" else "",
            )

            val inputs = buildList {
                add(compiledJar.toPath())
                addAll(resolvedDeps)
                addAll(runtimeJars)
                if (ktxCoreJar != null) add(ktxCoreJar)
            }

            val mainClass = readMainClass(compiledJar.toPath())
            outputJar.parent?.createDirectories()
            JarPacker(mainClass).pack(inputs, outputJar)

            val metadata = CompileMetadata(
                fatJar = outputJar,
                resolvedCoords = recording.snapshot().keys.toList(),
                mainClass = mainClass,
            )
            return ResultWithDiagnostics.Success(Unit, compileResult.reports) to metadata
        } finally {
            tmpCacheDir.toFile().deleteRecursively()
        }
    }

    /**
     * Kotlin jars required at runtime:
     *   - kotlin-stdlib (core language library)
     *   - kotlin-script-runtime (script base classes / TemplateWithArgs etc.)
     *   - kotlin-scripting-jvm (contains RunnerKt, called by the script class's main())
     *   - kotlin-scripting-common (transitive dep of RunnerKt: ScriptCompilationConfiguration etc.)
     *   - kotlin-reflect (the script class triggers reflection via ClassReference.getObjectInstance)
     *
     * [KotlinJars] supplies stdlib + script-runtime; the rest are located via
     * [Class.getProtectionDomain] (these jars are already on ktx's own classpath).
     */
    private fun collectRuntimeJars(): List<Path> {
        val jars = mutableListOf<Path>()
        for (jar in KotlinJars.kotlinScriptStandardJars) {
            if (jar != null && jar.exists()) jars.add(jar.toPath())
        }
        // RunnerKt in scripting-jvm is the entry point invoked directly from the script class's main()
        locateJarOf("kotlin.script.experimental.jvm.RunnerKt")?.let { jars.add(it) }
        // scripting-common provides API types like ScriptCompilationConfiguration
        locateJarOf("kotlin.script.experimental.api.ScriptCompilationConfiguration")?.let { jars.add(it) }
        // reflect: ConfigurationFromTemplateKt uses KClass.objectInstance during script bootstrap
        locateJarOf("kotlin.reflect.full.KClasses")?.let { jars.add(it) }
        return jars.distinct()
    }

    /**
     * Find the jar that contains a class via its ClassLoader. Returns null on failure.
     */
    private fun locateJarOf(className: String): Path? = runCatching {
        val cls = Class.forName(className)
        val src = cls.protectionDomain?.codeSource?.location ?: return null
        val path = Path.of(src.toURI())
        path.takeIf { it.toString().endsWith(".jar") && it.exists() }
    }.getOrNull()

    private fun locateKtxCoreJar(): Path? {
        val codeSource = KtsScript::class.java.protectionDomain?.codeSource?.location ?: return null
        val path = Path.of(codeSource.toURI())
        return path.takeIf { it.toString().endsWith(".jar") && it.exists() }
    }

    private fun readMainClass(jarPath: Path): String {
        java.util.jar.JarFile(jarPath.toFile()).use { jar ->
            val manifest = jar.manifest ?: error("$jarPath has no manifest")
            return manifest.mainAttributes.getValue("Main-Class")
                ?: error("$jarPath manifest is missing Main-Class")
        }
    }

    companion object {
        private const val MAIN_KTS_CACHE_DIR_PROPERTY = "kotlin.main.kts.compiled.scripts.cache.dir"
    }
}
