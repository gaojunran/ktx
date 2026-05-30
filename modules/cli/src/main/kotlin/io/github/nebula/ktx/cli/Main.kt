package io.github.nebula.ktx.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import io.github.nebula.ktx.cli.command.EvalCommand
import io.github.nebula.ktx.cli.command.RunCommand

/**
 * `ktx` CLI 入口。
 *
 * 行为约定（参考 uv / bun）：
 *   - `ktx run <script> [args...]` 显式 run 子命令；
 *   - `ktx <script> [args...]` 当第一个参数是已存在的文件或为 `-` 时，
 *     等价于 `ktx run <script> [args...]`；
 *   - `ktx -e "<expr>"` 求值一段表达式；
 *   - `ktx --help` / `ktx <subcommand> --help` 标准帮助。
 *
 * 默认子命令的实现没有依赖 clikt 私有钩子：在交给 clikt 解析前，
 * 我们先看 argv[0]，命中「是脚本」就把它前面塞个 `run`。这样 clikt
 * 的子命令分发逻辑保持纯粹，不需要 hack `invokedSubcommand`。
 */
class KtxCommand : CliktCommand(name = "ktx") {
    override fun run() = Unit
}

fun main(rawArgs: Array<String>) {
    val args = normalizeArgs(rawArgs)
    KtxCommand()
        .subcommands(RunCommand(), EvalCommand())
        .main(args)
}

/**
 * 没有显式子命令、且第一个参数像是脚本入参时，自动注入 `run`。
 *
 * 「像是脚本」的判断比较保守，避免把用户未来加的子命令名误当脚本：
 *   - `-` 是 stdin 哨兵；
 *   - 看着像选项（以 `-` 开头）：交给 clikt 自己处理；
 *   - 否则要求文件存在 —— 不存在就让 clikt 报「Unknown command」。
 */
private fun normalizeArgs(args: Array<String>): Array<String> {
    if (args.isEmpty()) return args
    val first = args[0]
    // `ktx -e "<expr>"` -> `ktx eval -e "<expr>"`
    if (first == "-e" || first == "--expr") return arrayOf("eval") + args
    if (first == "-") return arrayOf("run") + args
    if (first.startsWith("-")) return args  // --help / -h
    val knownSubcommands = setOf("run", "eval")
    if (first in knownSubcommands) return args
    // 当作脚本路径：存在就注入 run。不存在则交给 clikt 报未知命令，
    // 出来的错误更准确（"Got unexpected extra argument" 之流）。
    if (java.io.File(first).isFile) return arrayOf("run") + args
    return args
}
