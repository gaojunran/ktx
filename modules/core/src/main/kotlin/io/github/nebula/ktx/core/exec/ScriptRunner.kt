package io.github.nebula.ktx.core.exec

import io.github.nebula.ktx.core.script.KtsScript
import org.jetbrains.kotlin.mainKts.MainKtsEvaluationConfiguration
import org.jetbrains.kotlin.mainKts.MainKtsHostConfiguration
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.constructorArgs
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate
import kotlin.script.experimental.jvmhost.createJvmEvaluationConfigurationFromTemplate

/**
 * 编译并执行一个 .kts / .main.kts 脚本。
 *
 * 缓存目录通过系统属性传给 [MainKtsHostConfiguration]，让 main-kts 内置的
 * [kotlin.script.experimental.jvm.CompiledScriptJarsCache] 把编译产物落到
 * `~/.cache/ktx/compiled/`（XDG 规范，回退 `~/.cache/ktx/compiled/`）。
 *
 * 注意：这里不主动 unset 系统属性。CLI 整个进程生命周期就一份属性值，
 * 反复 set 是幂等的；后续 daemon 化后由 daemon 启动时 set 一次即可。
 */
class ScriptRunner(
    private val cacheDir: Path = defaultCacheDir(),
) {

    private val log = LoggerFactory.getLogger("ktx.runner")

    init {
        cacheDir.toFile().mkdirs()
        // main-kts 读这个 system property 决定缓存目录，见
        // org.jetbrains.kotlin.mainKts.MainKtsHostConfiguration。
        System.setProperty(MAIN_KTS_CACHE_DIR_PROPERTY, cacheDir.toAbsolutePath().toString())
    }

    /**
     * 跑指定脚本文件。
     *
     * @param scriptPath 脚本路径，可以是 `.kts` 或 `.main.kts`。
     * @param scriptArgs 透传给脚本的参数（脚本里通过构造器形参 `args` 拿到）。
     */
    fun run(scriptPath: Path, scriptArgs: Array<String>): ResultWithDiagnostics<EvaluationResult> {
        val scriptFile = scriptPath.absolute().toFile().also {
            require(it.isFile) { "脚本不存在：${it.absolutePath}" }
        }
        log.debug("running {} ({} bytes)", scriptFile.absolutePath, scriptFile.length())
        return evaluate(scriptFile.toScriptSource(), scriptArgs)
    }

    /**
     * 跑一段内联脚本源码（用于 `kts -e` 与 `kts run -` stdin）。
     *
     * @param source 完整脚本源码。
     * @param virtualName 用于诊断输出的虚拟文件名，例如 `<eval>` 或 `<stdin>`。
     */
    fun runInline(
        source: String,
        scriptArgs: Array<String>,
        virtualName: String = "<eval>",
    ): ResultWithDiagnostics<EvaluationResult> {
        log.debug("running inline script {} ({} chars)", virtualName, source.length)
        return evaluate(source.toScriptSource(virtualName), scriptArgs)
    }

    private fun evaluate(
        source: kotlin.script.experimental.api.SourceCode,
        scriptArgs: Array<String>,
    ): ResultWithDiagnostics<EvaluationResult> {
        val hostConfig = MainKtsHostConfiguration()
        val compilationConfig = createJvmCompilationConfigurationFromTemplate<KtsScript>(hostConfig)
        val evaluationConfig = createJvmEvaluationConfigurationFromTemplate<KtsScript>(hostConfig) {
            constructorArgs(scriptArgs)
        }
        return BasicJvmScriptingHost(hostConfig).eval(source, compilationConfig, evaluationConfig)
    }

    companion object {
        private const val MAIN_KTS_CACHE_DIR_PROPERTY = "kotlin.main.kts.compiled.scripts.cache.dir"

        /** XDG 规范：`$XDG_CACHE_HOME/ktx/compiled/`，回退 `$HOME/.cache/ktx/compiled/`。 */
        fun defaultCacheDir(): Path {
            val xdg = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
            val base = if (xdg != null) File(xdg) else File(System.getProperty("user.home"), ".cache")
            return File(base, "ktx/compiled").toPath()
        }
    }
}

/**
 * 把诊断输出写到合适的流：ERROR 走 stderr，其余走 stdout。
 * DEBUG 级别噪音过大（main-kts 会打印 jdk module 加载列表），过滤掉。
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
