package io.github.nebula.ktx.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import io.github.nebula.ktx.toolchain.ToolchainStore

/**
 * `ktx toolchain` subcommand group: manage JDKs that ktx itself downloaded.
 *
 * Subcommands:
 *   - `ktx toolchain list`: list all installed versions
 *   - `ktx toolchain install <major>`: install an LTS major (e.g. 21, 17)
 *   - `ktx toolchain path <major>`: print the absolute `bin/java` path,
 *     handy for shell use
 */
class ToolchainCommand : CliktCommand(name = "toolchain") {
    override fun help(context: Context): String = "Manage JDK installations used by @file:Toolchain"
    override fun run() = Unit
}

class ToolchainListCommand : CliktCommand(name = "list") {
    override fun help(context: Context): String = "List installed JDK versions"
    override fun run() {
        val store = ToolchainStore()
        val entries = store.list()
        if (entries.isEmpty()) {
            echo("(no JDK installed yet)")
            return
        }
        for (e in entries) {
            echo("${e.major}\t${e.semver}\t${e.id}")
        }
    }
}

class ToolchainInstallCommand : CliktCommand(name = "install") {
    override fun help(context: Context): String = "Download and install a JDK from Adoptium"
    private val majorArg by argument(name = "MAJOR", help = "JDK major version, e.g. 21 / 17")

    override fun run() {
        val major = majorArg.toIntOrNull() ?: error("major version must be an integer: $majorArg")
        val store = ToolchainStore()
        val install = store.install(major)
        echo("installed JDK $major: ${install.javaHome}")
    }
}

class ToolchainPathCommand : CliktCommand(name = "path") {
    override fun help(context: Context): String = "Print the bin/java path for an installed JDK"
    override fun helpEpilog(context: Context): String = """
        Useful for scripting:
          export JAVA_HOME="$(ktx toolchain path 17)"
    """.trimIndent()
    private val majorArg by argument(name = "MAJOR", help = "JDK major version")

    override fun run() {
        val major = majorArg.toIntOrNull() ?: error("major version must be an integer: $majorArg")
        val store = ToolchainStore()
        val install = store.find(major) ?: error("JDK $major not installed (run `ktx toolchain install $major` first)")
        echo(install.javaBin.toAbsolutePath().toString())
    }
}
