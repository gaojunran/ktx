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
 * Resolve the script's dependencies and write the result to `<script>.lock`.
 * The script body is not executed. Header `@file:` annotations are kept,
 * but the body is replaced with `Unit` so only dependency resolution runs.
 *
 * Workflow:
 *   1. Drive a single compile through [LockingFlow.lockAndOptionallyRun];
 *   2. RecordingResolver records the resolved dependencies as a side channel;
 *   3. Write the lockfile next to the script with a `.lock` suffix.
 */
class LockCommand : CliktCommand(name = "lock") {

    private val scriptArg by argument(
        name = "SCRIPT",
        help = "Script path",
    )

    override fun run() {
        val path = Path(scriptArg)
        require(path.exists() && path.isRegularFile()) { "script not found: $scriptArg" }

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
