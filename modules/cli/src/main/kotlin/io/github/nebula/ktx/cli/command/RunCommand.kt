package io.github.nebula.ktx.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import io.github.nebula.ktx.cli.meta.ToolchainHeaderScanner
import io.github.nebula.ktx.core.exec.ScriptRunner
import io.github.nebula.ktx.core.exec.printReports
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.system.exitProcess

/**
 * `ktx run <script> [script-args...]`
 *
 * 当 `<script>` 是 `-` 时，从 stdin 读取脚本源码（参考 `uv run -` / `bun run -`）。
 *
 * 第一阶段还不支持 `--frozen`、`--no-cache` 等选项，这些在后续 lockfile / 缓存
 * 子任务里加。这里保持表面最小，先把核心路径打通。
 */
class RunCommand : CliktCommand(name = "run") {

    private val log = LoggerFactory.getLogger("ktx.cli.run")

    private val scriptArg by argument(
        name = "SCRIPT",
        help = "脚本路径，或 `-` 表示从标准输入读取",
    )
    private val scriptArgs by argument(name = "ARGS", help = "传给脚本的参数").multiple()

    override fun run() {
        val runner = ScriptRunner()
        val result = if (scriptArg == "-") {
            val source = System.`in`.bufferedReader().readText()
            runner.runInline(source, scriptArgs.toTypedArray(), virtualName = "<stdin>")
        } else {
            val path = resolveScript(scriptArg)
            preflightToolchain(path)
            runner.run(path, scriptArgs.toTypedArray())
        }
        result.printReports()
        exitProcess(if (result is ResultWithDiagnostics.Success) 0 else 1)
    }

    private fun resolveScript(arg: String): Path {
        val path = Path(arg)
        require(path.exists() && path.isRegularFile()) { "脚本不存在：$arg" }
        return path
    }

    /**
     * Phase 1：仅做工具链注解的「前置校验」。
     *   - `kotlin = "..."` 与 CLI 自带版本不一致就报错（多版本支持留到 Phase 2）；
     *   - `jdk = "..."` 暂时只记录到日志，等 Toolchain 模块上线后改为真实路由。
     *
     * 这一步做在编译之前，让用户尽早看到「版本不匹配」错误，避免编译跑半天才崩。
     */
    private fun preflightToolchain(scriptPath: Path) {
        val tc = ToolchainHeaderScanner.scan(scriptPath)
        if (tc.kotlin.isNotEmpty() && tc.kotlin != BuildInfo.kotlinVersion) {
            throw IllegalStateException(
                "脚本声明 Kotlin ${tc.kotlin}，但当前 CLI 自带 ${BuildInfo.kotlinVersion}；" +
                        "多 Kotlin 版本支持将在 Phase 2 通过 daemon 路由提供。",
            )
        }
        if (tc.jdk.isNotEmpty()) {
            log.info("脚本声明 JDK {}（Phase 1 暂未实现自动下载，将沿用当前 JVM）", tc.jdk)
        }
    }
}
