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
 * 把脚本编译成独立 fat jar，可被 `java -jar` 直接执行，不依赖 ktx 也不依赖
 * Kotlin 编译器 —— end user 只需 JRE 17+。
 *
 * 默认输出路径：`<script-basename>.jar`，与脚本同目录。
 */
class CompileCommand : CliktCommand(name = "compile") {

    private val scriptArg by argument(name = "SCRIPT", help = "待编译脚本路径")

    private val outputOption by option(
        "-o", "--output",
        help = "输出 jar 路径（默认与脚本同目录、同名 .jar）",
    )

    override fun run() {
        val scriptPath = Path(scriptArg)
        require(scriptPath.exists() && scriptPath.isRegularFile()) { "脚本不存在：$scriptArg" }

        val outputJar = outputOption?.let { Path(it) }
            ?: scriptPath.resolveSibling(stripScriptSuffix(scriptPath.fileName.toString()) + ".jar")

        echo("编译 ${scriptPath.fileName} -> $outputJar")
        val result = CompileFlow().compile(scriptPath, outputJar)
        result.printReports()
        if (result is ResultWithDiagnostics.Failure) {
            exitProcess(1)
        }
        val sizeKb = outputJar.toFile().length() / 1024
        echo("产物：$outputJar ($sizeKb KB)")
        echo("运行：java -jar $outputJar [args...]")
    }

    /** 去掉 `.kts` / `.main.kts` 后缀，剩下脚本基础名。 */
    private fun stripScriptSuffix(name: String): String = when {
        name.endsWith(".main.kts") -> name.removeSuffix(".main.kts")
        name.endsWith(".kts") -> name.removeSuffix(".kts")
        else -> name
    }
}
