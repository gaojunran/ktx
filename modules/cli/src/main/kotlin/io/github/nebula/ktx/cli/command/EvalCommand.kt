package io.github.nebula.ktx.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import io.github.nebula.ktx.core.exec.ScriptRunner
import io.github.nebula.ktx.core.exec.printReports
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.system.exitProcess

/**
 * `ktx eval -e "<expr>" [args...]`，等价快捷写法 `ktx -e "<expr>"`（由 Main 重写为
 * `eval -e ...`，这里通过 option `-e` 给同样的子命令复用）。
 *
 * Phase 1 的最小化语义：把表达式字符串当作完整脚本编译执行；不自动包 println，
 * 让用户自己决定是否打印。这避免了「值有副作用 / 是 Unit / 是协程构建器」时
 * 的歧义。
 */
class EvalCommand : CliktCommand(name = "eval") {

    private val expr by option("-e", "--expr", help = "要执行的 Kotlin 表达式或语句").required()
    private val scriptArgs by argument(name = "ARGS", help = "传给脚本的参数").multiple()

    override fun run() {
        val result = ScriptRunner().runInline(
            source = expr,
            scriptArgs = scriptArgs.toTypedArray(),
            virtualName = "<eval>",
        )
        result.printReports()
        exitProcess(if (result is ResultWithDiagnostics.Success) 0 else 1)
    }
}
