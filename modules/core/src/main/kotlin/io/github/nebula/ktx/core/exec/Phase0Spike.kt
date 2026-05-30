package io.github.nebula.ktx.core.exec

import org.jetbrains.kotlin.mainKts.MainKtsScript
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.constructorArgs
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate
import kotlin.script.experimental.jvmhost.createJvmEvaluationConfigurationFromTemplate
import kotlin.system.exitProcess

/**
 * Phase 0 可行性原型：直接复用官方 kotlin-main-kts 的 ScriptDefinition，
 * 用 BasicJvmScriptingHost 跑通一个 .main.kts 文件。
 *
 * 这一阶段只验证：
 *   1. Kotlin 2.2.0 下嵌入式编译器能正常工作；
 *   2. @file:DependsOn / @file:Repository / @file:Import 等注解的依赖解析
 *      不需要我们写一行代码，全部由 MainKtsConfigurator 完成。
 *
 * 后续 Phase 1 会把这个 spike 替换为 KtsScript（自定义 ScriptDefinition），
 * 并加上工具链路由、缓存目录注入、lockfile 等。
 */

private val log = LoggerFactory.getLogger("ktx.phase0")

fun main(rawArgs: Array<String>) {
    if (rawArgs.isEmpty()) {
        System.err.println("usage: phase0-spike <script.kts | script.main.kts> [script-args...]")
        exitProcess(2)
    }

    val scriptFile = File(rawArgs[0]).also {
        require(it.isFile) { "脚本不存在：${it.absolutePath}" }
    }
    val scriptArgs = rawArgs.drop(1).toTypedArray()

    log.info("running {} ({} bytes)", scriptFile.absolutePath, scriptFile.length())

    val startedNs = System.nanoTime()
    val result = evalMainKts(scriptFile, scriptArgs)
    val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000

    result.reports.forEach { report ->
        // DEBUG 诊断（如 "Loading modules: ..."）噪音太大，只对 INFO 及以上输出。
        if (report.severity < ScriptDiagnostic.Severity.INFO) return@forEach
        val target = if (report.severity >= ScriptDiagnostic.Severity.ERROR) System.err else System.out
        target.println(formatReport(report))
    }

    val maxSeverity = result.reports.maxOfOrNull { it.severity } ?: ScriptDiagnostic.Severity.DEBUG
    log.info("done in {} ms (max severity = {})", elapsedMs, maxSeverity)

    exitProcess(if (result is ResultWithDiagnostics.Success) 0 else 1)
}

private fun evalMainKts(scriptFile: File, args: Array<String>): ResultWithDiagnostics<EvaluationResult> {
    val compilationConfig = createJvmCompilationConfigurationFromTemplate<MainKtsScript>()
    val evaluationConfig = createJvmEvaluationConfigurationFromTemplate<MainKtsScript> {
        constructorArgs(args)
    }
    return BasicJvmScriptingHost().eval(
        scriptFile.toScriptSource(),
        compilationConfig,
        evaluationConfig,
    )
}

private fun formatReport(r: ScriptDiagnostic): String {
    val location = r.location?.let { " (${it.start.line}:${it.start.col})" }.orEmpty()
    val source = r.sourcePath?.let { " in $it" }.orEmpty()
    return "[${r.severity}]$source$location ${r.message}"
}
