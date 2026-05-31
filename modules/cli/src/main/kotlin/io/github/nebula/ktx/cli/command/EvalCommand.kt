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
 * `ktx eval -e "<expr>" [args...]`. The shortcut `ktx -e "<expr>"` is rewritten
 * by Main to `eval -e ...` and reuses this same subcommand via the `-e` option.
 *
 * Phase 1 minimal semantics: treat the expression string as a complete script
 * and compile/execute it; do not auto-wrap with println, leaving the user to
 * decide whether to print. This avoids ambiguity when the value has side
 * effects, is Unit, or is a coroutine builder.
 */
class EvalCommand : CliktCommand(name = "eval") {

    private val expr by option("-e", "--expr", help = "Kotlin expression or statement to execute").required()
    private val scriptArgs by argument(name = "ARGS", help = "Arguments forwarded to the script").multiple()

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
