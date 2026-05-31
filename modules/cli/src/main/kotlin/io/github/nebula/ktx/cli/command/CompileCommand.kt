package io.github.nebula.ktx.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import io.github.nebula.ktx.core.compile.CompileFlow
import io.github.nebula.ktx.core.exec.printReports
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.system.exitProcess

/**
 * `ktx compile <script> [-o <output.jar>]`
 *
 * Compile the script into a self-contained fat jar that can be executed
 * directly with `java -jar`, depending on neither ktx nor the Kotlin
 * compiler. End users only need a JRE 17+.
 *
 * Default output path: `<script-basename>.jar` next to the script.
 */
class CompileCommand : CliktCommand(name = "compile") {

    private val scriptArg by argument(name = "SCRIPT", help = "Script path to compile")

    private val outputOption by option(
        "-o", "--output",
        help = "Output jar path (defaults to a sibling .jar next to the script)",
    )

    override fun run() {
        val scriptPath = Path(scriptArg)
        require(scriptPath.exists() && scriptPath.isRegularFile()) { "script not found: $scriptArg" }

        val outputJar = outputOption?.let { Path(it) }
            ?: scriptPath.resolveSibling(stripScriptSuffix(scriptPath.fileName.toString()) + ".jar")

        echo("compiling ${scriptPath.fileName} -> $outputJar")
        val result = CompileFlow().compile(scriptPath, outputJar)
        result.printReports()
        if (result is ResultWithDiagnostics.Failure) {
            exitProcess(1)
        }
        val sizeKb = outputJar.toFile().length() / 1024
        echo("artifact: $outputJar ($sizeKb KB)")
        echo("run with: java -jar $outputJar [args...]")
    }

    /** Strip `.kts` / `.main.kts` suffix to get the base script name. */
    private fun stripScriptSuffix(name: String): String = when {
        name.endsWith(".main.kts") -> name.removeSuffix(".main.kts")
        name.endsWith(".kts") -> name.removeSuffix(".kts")
        else -> name
    }
}
