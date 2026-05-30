package io.github.nebula.ktx.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import io.github.nebula.ktx.core.deps.LockingFlow
import io.github.nebula.ktx.core.exec.ScriptRunner
import io.github.nebula.ktx.core.exec.printReports
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.system.exitProcess

/**
 * `ktx lock <script>`
 *
 * 解析脚本依赖、把结果落到 `<script>.lock`。脚本主体不会被执行 ——
 * 头部 `@file:` 注解保留，主体替换成 `Unit`，目的是只跑依赖解析。
 *
 * 工作流：
 *   1. 用 [LockingFlow.lockAndOptionallyRun] 走一遍编译；
 *   2. RecordingResolver 旁路记录解析结果；
 *   3. 完成后写 lockfile，路径与脚本同目录、加 `.lock` 后缀。
 */
class LockCommand : CliktCommand(name = "lock") {

    private val scriptArg by argument(
        name = "SCRIPT",
        help = "脚本路径",
    )

    override fun run() {
        val path = Path(scriptArg)
        require(path.exists() && path.isRegularFile()) { "脚本不存在：$scriptArg" }

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
}
