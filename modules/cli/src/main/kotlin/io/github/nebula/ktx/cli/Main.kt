package io.github.nebula.ktx.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import io.github.nebula.ktx.cli.command.AddCommand
import io.github.nebula.ktx.cli.command.CacheCleanCommand
import io.github.nebula.ktx.cli.command.CacheCommand
import io.github.nebula.ktx.cli.command.CacheGcCommand
import io.github.nebula.ktx.cli.command.CacheInfoCommand
import io.github.nebula.ktx.cli.command.CompileCommand
import io.github.nebula.ktx.cli.command.DaemonCommand
import io.github.nebula.ktx.cli.command.DaemonLogsCommand
import io.github.nebula.ktx.cli.command.DaemonStatusCommand
import io.github.nebula.ktx.cli.command.DaemonStopCommand
import io.github.nebula.ktx.cli.command.EvalCommand
import io.github.nebula.ktx.cli.command.LockCommand
import io.github.nebula.ktx.cli.command.RunCommand
import io.github.nebula.ktx.cli.command.ToolchainCommand
import io.github.nebula.ktx.cli.command.ToolchainInstallCommand
import io.github.nebula.ktx.cli.command.ToolchainListCommand
import io.github.nebula.ktx.cli.command.ToolchainPathCommand
import io.github.nebula.ktx.cli.command.UsageCommand

/**
 * `ktx` CLI entry point.
 *
 * Behavior (modeled after uv / bun):
 *   - `ktx run <script> [args...]` explicit run subcommand;
 *   - `ktx <script> [args...]` when the first argument is an existing file or `-`,
 *     this is equivalent to `ktx run <script> [args...]`;
 *   - `ktx -e "<expr>"` evaluates an expression;
 *   - `ktx --help` / `ktx <subcommand> --help` standard help.
 *
 * The default subcommand is implemented without relying on clikt internals:
 * we inspect argv[0] before handing off to clikt, and prepend `run` when it
 * looks like a script. This keeps clikt's subcommand dispatch logic clean
 * without hacking `invokedSubcommand`.
 */
class KtxCommand : CliktCommand(name = "ktx") {
    override fun run() = Unit
}

fun main(rawArgs: Array<String>) {
    OriginalArgs.set(rawArgs)
    val args = normalizeArgs(rawArgs)
    val toolchain = ToolchainCommand()
        .subcommands(ToolchainListCommand(), ToolchainInstallCommand(), ToolchainPathCommand())
    val daemon = DaemonCommand()
        .subcommands(DaemonStatusCommand(), DaemonStopCommand(), DaemonLogsCommand())
    val cache = CacheCommand()
        .subcommands(CacheInfoCommand(), CacheCleanCommand(), CacheGcCommand())
    // UsageCommand needs a fully-assembled root to walk; the lambda lets
    // it look the root up at run() time, after subcommands are attached.
    val root = KtxCommand()
    root
        .subcommands(
            RunCommand(), EvalCommand(), LockCommand(), AddCommand(), CompileCommand(),
            toolchain, daemon, cache,
            UsageCommand { root },
        )
        .main(args)
}

/**
 * Inject `run` automatically when there's no explicit subcommand and the first
 * argument looks like a script.
 *
 * The "looks like a script" check is conservative to avoid mistaking future
 * subcommand names as scripts:
 *   - `-` is the stdin sentinel;
 *   - looks like an option (starts with `-`): let clikt handle it;
 *   - otherwise the file must exist; if not, let clikt report "Unknown command".
 */
private fun normalizeArgs(args: Array<String>): Array<String> {
    if (args.isEmpty()) return args
    val first = args[0]
    // `ktx -e "<expr>"` -> `ktx eval -e "<expr>"`
    if (first == "-e" || first == "--expr") return arrayOf("eval") + args
    if (first == "-") return arrayOf("run") + args
    if (first.startsWith("-")) return args  // --help / -h
    val knownSubcommands = setOf("run", "eval", "lock", "add", "compile", "toolchain", "daemon", "cache", "usage")
    if (first in knownSubcommands) return args
    // Treat as a script path: inject `run` if it exists. Otherwise let clikt
    // report unknown command, which gives a more accurate error message
    // ("Got unexpected extra argument" and friends).
    if (java.io.File(first).isFile) return arrayOf("run") + args
    return args
}
