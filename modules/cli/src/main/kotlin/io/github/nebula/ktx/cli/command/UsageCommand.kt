package io.github.nebula.ktx.cli.command

import clikt_usage.generate
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context

/**
 * `ktx usage`
 *
 * Emit a [usage spec](https://usage.jdx.dev/) (KDL) to stdout, derived from
 * the live clikt command tree of the current ktx binary. Consumed by the
 * `usage` CLI to generate shell completions, markdown reference, and
 * man-pages.
 *
 * The `mise run render` task pipes this into
 *   usage generate markdown -m -f - --out-dir docs/cli --url-prefix /cli
 * so the docs/cli/ tree under VitePress is fully derived from the CLI here.
 *
 * Implementation note: clikt-usage's `generate(root)` walks `root`'s
 * subcommand tree. We must hand it the same fully-assembled `KtxCommand`
 * that `Main.kt` constructs (with every subcommand attached), not a fresh
 * empty instance — otherwise the spec would only contain `usage` itself.
 * The constructor takes a lambda so the root can be referenced before
 * subcommands are wired up, breaking the chicken-and-egg.
 */
class UsageCommand(private val rootSupplier: () -> CliktCommand) : CliktCommand(name = "usage") {
    override fun help(context: Context): String = "Emit a usage spec (KDL) for shell completions and doc generation"
    override fun run() {
        echo(generate(rootSupplier()))
    }
}
