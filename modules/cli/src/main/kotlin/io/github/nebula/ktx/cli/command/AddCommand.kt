package io.github.nebula.ktx.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
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
 * Insert a new `@file:DependsOn("coord")` line at the top of the script,
 * then refresh the lockfile.
 *
 * Insertion point: right after the shebang, alongside other `@file:`
 * annotations (at the end of that block). No deduplication: if the user
 * adds the same coordinate twice, two `@file:DependsOn` lines result and
 * the compiler merges them naturally; simplicity beats cleverness here.
 */
class AddCommand : CliktCommand(name = "add") {
    override fun help(context: Context): String = "Add a dependency to a script and refresh its lockfile"
    override fun helpEpilog(context: Context): String = """
        Inserts @file:DependsOn("coord") into the script header, then
        runs `ktx lock` to refresh the lockfile. Use --no-lock to skip
        the lockfile refresh.

        Example:
          ktx add com.squareup.moshi:moshi-kotlin:1.15.2 app.main.kts
    """.trimIndent()

    private val coordArg by argument(name = "COORDINATES", help = "Maven coordinates, e.g. io.ktor:ktor-client-core-jvm:3.2.0")
    private val scriptArg by argument(name = "SCRIPT", help = "Script path")
    private val skipLock by option("--no-lock", help = "Edit the script only; skip lockfile refresh").flag()

    override fun run() {
        val path = Path(scriptArg)
        require(path.exists() && path.isRegularFile()) { "script not found: $scriptArg" }

        val original = path.readText()
        val updated = insertDependsOn(original, coordArg)
        if (updated == original) {
            echo("script unchanged (coordinate already present)")
        } else {
            path.writeText(updated)
            echo("added @file:DependsOn(\"$coordArg\") to ${path.fileName}")
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
     * Insert `@file:DependsOn("coord")` at the end of the script's `@file:`
     * header block.
     *
     * Rules:
     *   - the shebang stays on the first line;
     *   - within the contiguous run of `@file:` / blank / `//` lines after
     *     the shebang, find the last `@file:` and insert just after it;
     *   - if no `@file:` annotation exists yet, insert after the shebang
     *     (or at the start of the file).
     *
     * Dedup: if the same coordinate already appears in a `@file:DependsOn`,
     * the original text is returned unchanged.
     */
    private fun insertDependsOn(source: String, coord: String): String {
        val newAnnot = "@file:DependsOn(\"$coord\")"
        if (source.contains("@file:DependsOn(\"$coord\")")) return source

        val lines = source.lines().toMutableList()
        var insertAt = 0
        if (lines.isNotEmpty() && lines[0].startsWith("#!")) insertAt = 1

        // Find the last @file: line within the header section.
        var lastFileLine = -1
        var i = insertAt
        while (i < lines.size) {
            val l = lines[i].trim()
            when {
                l.isEmpty() -> { /* accept blank lines */ }
                l.startsWith("//") -> { /* accept line comments */ }
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
