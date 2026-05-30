package io.github.nebula.ktx.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.github.nebula.ktx.core.deps.LockingFlow
import io.github.nebula.ktx.core.exec.ScriptRunner
import io.github.nebula.ktx.core.exec.printReports
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.system.exitProcess

/**
 * `ktx add <coord> <script>`
 *
 * 在脚本顶部插入一行 `@file:DependsOn("coord")`，然后刷新 lockfile。
 *
 * 插入位置：紧跟在 shebang 之后、其他 `@file:` 注解之间（最末尾）。
 * 不做存在性去重 —— 用户重复 `add` 同一坐标会得到两条 `@file:DependsOn`，
 * 编译器会自然合并；保持简单胜过精巧。
 */
class AddCommand : CliktCommand(name = "add") {

    private val coordArg by argument(name = "COORDINATES", help = "Maven 坐标，如 io.ktor:ktor-client-core-jvm:3.2.0")
    private val scriptArg by argument(name = "SCRIPT", help = "脚本路径")
    private val skipLock by option("--no-lock", help = "仅修改脚本，不刷新 lockfile").flag()

    override fun run() {
        val path = Path(scriptArg)
        require(path.exists() && path.isRegularFile()) { "脚本不存在：$scriptArg" }

        val original = path.readText()
        val updated = insertDependsOn(original, coordArg)
        if (updated == original) {
            echo("脚本未变化（坐标已存在）")
        } else {
            path.writeText(updated)
            echo("已添加 @file:DependsOn(\"$coordArg\") 到 ${path.fileName}")
        }

        if (skipLock) return

        val runner = ScriptRunner()
        val result = LockingFlow.lockAndOptionallyRun(
            runner = runner,
            scriptPath = path,
            scriptArgs = emptyArray(),
            skipExecution = true,
        )
        result.printReports()
        exitProcess(if (result is ResultWithDiagnostics.Success) 0 else 1)
    }

    /**
     * 把 `@file:DependsOn("coord")` 插到脚本头部 `@file:` 区块的末尾。
     *
     * 处理规则：
     *   - shebang 永远保留在第一行；
     *   - 在第一个 shebang 之后、连续的 `@file:` / 空行 / `//` 行注释 之内
     *     找到最后一个 `@file:` 的位置，紧随其后插入；
     *   - 没有任何 `@file:` 注解时，插到 shebang 之后（或文件最开头）。
     *
     * 重复检测：若同坐标已存在于 `@file:DependsOn` 中，返回原文不变。
     */
    private fun insertDependsOn(source: String, coord: String): String {
        val newAnnot = "@file:DependsOn(\"$coord\")"
        if (source.contains("@file:DependsOn(\"$coord\")")) return source

        val lines = source.lines().toMutableList()
        var insertAt = 0
        if (lines.isNotEmpty() && lines[0].startsWith("#!")) insertAt = 1

        // 在 head 区域内找最后一个 @file: 行
        var lastFileLine = -1
        var i = insertAt
        while (i < lines.size) {
            val l = lines[i].trim()
            when {
                l.isEmpty() -> { /* 接受空行 */ }
                l.startsWith("//") -> { /* 接受行注释 */ }
                l.startsWith("@file:") -> lastFileLine = i
                else -> break
            }
            i++
        }
        val finalInsert = if (lastFileLine >= 0) lastFileLine + 1 else insertAt
        lines.add(finalInsert, newAnnot)
        return lines.joinToString("\n")
    }
}
