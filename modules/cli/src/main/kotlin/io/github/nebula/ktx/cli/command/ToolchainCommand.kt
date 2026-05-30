package io.github.nebula.ktx.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import io.github.nebula.ktx.toolchain.ToolchainStore

/**
 * `ktx toolchain` 子命令组：管理 ktx 自己下载的 JDK。
 *
 * 子命令：
 *   - `ktx toolchain list`：列出所有已装版本
 *   - `ktx toolchain install <major>`：装一个 LTS 主版本（如 21、17）
 *   - `ktx toolchain path <major>`：打印 `bin/java` 绝对路径，方便 shell 用
 */
class ToolchainCommand : CliktCommand(name = "toolchain") {
    override fun run() = Unit
}

class ToolchainListCommand : CliktCommand(name = "list") {
    override fun run() {
        val store = ToolchainStore()
        val entries = store.list()
        if (entries.isEmpty()) {
            echo("（尚未安装任何 JDK）")
            return
        }
        for (e in entries) {
            echo("${e.major}\t${e.semver}\t${e.id}")
        }
    }
}

class ToolchainInstallCommand : CliktCommand(name = "install") {
    private val majorArg by argument(name = "MAJOR", help = "JDK 主版本号，如 21 / 17")

    override fun run() {
        val major = majorArg.toIntOrNull() ?: error("主版本必须是整数：$majorArg")
        val store = ToolchainStore()
        val install = store.install(major)
        echo("已安装 JDK $major：${install.javaHome}")
    }
}

class ToolchainPathCommand : CliktCommand(name = "path") {
    private val majorArg by argument(name = "MAJOR", help = "JDK 主版本号")

    override fun run() {
        val major = majorArg.toIntOrNull() ?: error("主版本必须是整数：$majorArg")
        val store = ToolchainStore()
        val install = store.find(major) ?: error("未安装 JDK $major（先 `ktx toolchain install $major`）")
        echo(install.javaBin.toAbsolutePath().toString())
    }
}
