package io.github.nebula.ktx.core.exec

import io.github.nebula.ktx.core.script.KtsScript
import io.github.nebula.ktx.core.script.withResolverOverride
import org.jetbrains.kotlin.mainKts.MainKtsHostConfiguration
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.api.constructorArgs
import kotlin.script.experimental.dependencies.ExternalDependenciesResolver
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate
import kotlin.script.experimental.jvmhost.createJvmEvaluationConfigurationFromTemplate

/**
 * Compiles and executes a `.kts` / `.main.kts` script.
 *
 * The cache directory is passed to [MainKtsHostConfiguration] via system property,
 * so main-kts's built-in [kotlin.script.experimental.jvm.CompiledScriptJarsCache]
 * writes compiled artifacts into `~/.cache/ktx/compiled/` (XDG spec, falls back
 * to `~/.cache/ktx/compiled/`).
 *
 * Note: we don't unset the system property. The CLI process holds a single value
 * for its lifetime, and repeated sets are idempotent. Once daemonized, the daemon
 * sets it once at startup.
 */
class ScriptRunner(
    private val cacheDir: Path = defaultCacheDir(),
) {

    private val log = LoggerFactory.getLogger("ktx.runner")

    init {
        cacheDir.toFile().mkdirs()
        // main-kts reads this system property to decide the cache directory; see
        // org.jetbrains.kotlin.mainKts.MainKtsHostConfiguration.
        System.setProperty(MAIN_KTS_CACHE_DIR_PROPERTY, cacheDir.toAbsolutePath().toString())
    }

    /**
     * Run a script file.
     *
     * @param scriptPath script path; can be `.kts` or `.main.kts`.
     * @param scriptArgs arguments forwarded to the script (received via the constructor parameter `args`).
     * @param resolver optional dependency resolver; defaults to main-kts's network-backed one if null.
     * @param bypassCompiledCache skip the compiled-artifact cache (must be true during lock,
     *                            otherwise a cache hit prevents RecordingResolver from observing resolutions).
     */
    fun run(
        scriptPath: Path,
        scriptArgs: Array<String>,
        resolver: ExternalDependenciesResolver? = null,
        bypassCompiledCache: Boolean = false,
    ): ResultWithDiagnostics<EvaluationResult> {
        val scriptFile = scriptPath.absolute().toFile().also {
            require(it.isFile) { "script not found: ${it.absolutePath}" }
        }
        log.debug("running {} ({} bytes)", scriptFile.absolutePath, scriptFile.length())
        return evaluate(scriptFile.toScriptSource(), scriptArgs, resolver, bypassCompiledCache)
    }

    /**
     * Run an inline script source (for `kts -e` and `kts run -` over stdin).
     *
     * @param source full script source.
     * @param virtualName virtual file name shown in diagnostics, e.g. `<eval>` or `<stdin>`.
     */
    fun runInline(
        source: String,
        scriptArgs: Array<String>,
        virtualName: String = "<eval>",
        resolver: ExternalDependenciesResolver? = null,
        bypassCompiledCache: Boolean = false,
    ): ResultWithDiagnostics<EvaluationResult> {
        log.debug("running inline script {} ({} chars)", virtualName, source.length)
        return evaluate(source.toScriptSource(virtualName), scriptArgs, resolver, bypassCompiledCache)
    }

    private fun evaluate(
        source: SourceCode,
        scriptArgs: Array<String>,
        resolver: ExternalDependenciesResolver?,
        bypassCompiledCache: Boolean,
    ): ResultWithDiagnostics<EvaluationResult> = withResolverOverride(resolver) {
        // The resolverOverride ThreadLocal must be set BEFORE
        // createJvmCompilationConfigurationFromTemplate runs — the
        // ScriptCompilationConfiguration { ... } block in the template is
        // instantiated at that point, and KtsScriptDefinition reads the ThreadLocal then.
        //
        // bypassCompiledCache: temporarily redirect the system property to a
        // non-existent tmp directory (one-shot, named by nanoTime).
        // MainKtsHostConfiguration reads this property at startup and treats
        // "directory exists" as the cache-hit precondition; pointing it at a
        // non-existent path disables cache writes and forces recompilation.
        // Restored immediately after lock completes.
        val origCacheDir = System.getProperty(MAIN_KTS_CACHE_DIR_PROPERTY)
        if (bypassCompiledCache) {
            // Set to empty: MainKtsHostConfiguration's logic
            //   `cacheExtSetting.isBlank() -> null` disables the cache outright.
            System.setProperty(MAIN_KTS_CACHE_DIR_PROPERTY, "")
        }
        try {
            val hostConfig = MainKtsHostConfiguration()
            val compilationConfig = createJvmCompilationConfigurationFromTemplate<KtsScript>(hostConfig)
            val evaluationConfig = createJvmEvaluationConfigurationFromTemplate<KtsScript>(hostConfig) {
                constructorArgs(scriptArgs)
            }
            BasicJvmScriptingHost(hostConfig).eval(source, compilationConfig, evaluationConfig)
        } finally {
            if (bypassCompiledCache) {
                if (origCacheDir != null) System.setProperty(MAIN_KTS_CACHE_DIR_PROPERTY, origCacheDir)
                else System.clearProperty(MAIN_KTS_CACHE_DIR_PROPERTY)
            }
        }
    }

    companion object {
        private const val MAIN_KTS_CACHE_DIR_PROPERTY = "kotlin.main.kts.compiled.scripts.cache.dir"

        /** XDG spec: `$XDG_CACHE_HOME/ktx/compiled/`, falls back to `$HOME/.cache/ktx/compiled/`. */
        fun defaultCacheDir(): Path {
            val xdg = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
            val base = if (xdg != null) File(xdg) else File(System.getProperty("user.home"), ".cache")
            return File(base, "ktx/compiled").toPath()
        }
    }
}

/**
 * Routes diagnostic output to the right stream: ERROR goes to stderr, others to stdout.
 * DEBUG is too noisy (main-kts prints the JDK module load list) so it's filtered out.
 */
fun ResultWithDiagnostics<*>.printReports() {
    reports.forEach { report ->
        if (report.severity < ScriptDiagnostic.Severity.INFO) return@forEach
        val target = if (report.severity >= ScriptDiagnostic.Severity.ERROR) System.err else System.out
        target.println(formatReport(report))
    }
}

private fun formatReport(r: ScriptDiagnostic): String {
    val location = r.location?.let { " (${it.start.line}:${it.start.col})" }.orEmpty()
    val source = r.sourcePath?.let { " in $it" }.orEmpty()
    return "[${r.severity}]$source$location ${r.message}"
}
