package io.github.nebula.ktx.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.github.nebula.ktx.cli.ToolchainDispatcher
import io.github.nebula.ktx.cli.ipc.DaemonLifecycle
import io.github.nebula.ktx.cli.meta.ToolchainHeaderScanner
import io.github.nebula.ktx.core.deps.FrozenResolver
import io.github.nebula.ktx.core.deps.Lockfile
import io.github.nebula.ktx.core.deps.LockingFlow
import io.github.nebula.ktx.core.exec.ScriptRunner
import io.github.nebula.ktx.core.exec.printReports
import io.github.nebula.ktx.toolchain.ToolchainStore
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
 * 选项：
 *   - `--frozen`：仅按 `<script>.lock` 解析依赖，禁止联网（CI 友好）；缺
 *     lockfile 直接报错。
 *   - `--lock`：在线解析依赖并写/刷新 `<script>.lock`，再正常执行脚本。
 *   - `--daemon` / 环境变量 `KTX_DAEMON=1`：通过 ktx daemon 跑，避免编译器
 *     冷启动开销（Phase 2.1 起）。daemon 不在跑时自动 fork。
 *
 * `--frozen` / `--lock` 与 `--daemon` 互斥（Phase 2.1 daemon 暂不支持注入
 * 自定义 resolver；Phase 2.2 接通）。
 */
class RunCommand : CliktCommand(name = "run") {

    private val log = LoggerFactory.getLogger("ktx.cli.run")

    private val frozen by option("--frozen", help = "仅按 lockfile 解析依赖，禁止联网").flag()
    private val refreshLock by option("--lock", help = "在线运行并刷新 lockfile").flag()
    private val daemon by option(
        "--daemon",
        help = "通过 ktx daemon 跑（消除编译器冷启动）。也可设环境变量 KTX_DAEMON=1。",
    ).flag(default = System.getenv("KTX_DAEMON") == "1")

    private val scriptArg by argument(
        name = "SCRIPT",
        help = "脚本路径，或 `-` 表示从标准输入读取",
    )
    private val scriptArgs by argument(name = "ARGS", help = "传给脚本的参数").multiple()

    override fun run() {
        require(!(frozen && refreshLock)) { "--frozen 与 --lock 互斥" }
        require(!(daemon && refreshLock)) {
            "--lock 暂不支持 --daemon（lock 流程要禁用编译缓存，daemon 共享缓存场景下逻辑未定）"
        }

        if (daemon) {
            runViaDaemon()
            return  // unreachable
        }

        val runner = ScriptRunner()
        val result = when {
            scriptArg == "-" -> {
                require(!frozen) { "stdin 模式不支持 --frozen（没有对应的 lockfile）" }
                require(!refreshLock) { "stdin 模式不支持 --lock" }
                val source = System.`in`.bufferedReader().readText()
                runner.runInline(source, scriptArgs.toTypedArray(), virtualName = "<stdin>")
            }
            else -> runFile(runner)
        }
        result.printReports()
        exitProcess(if (result is ResultWithDiagnostics.Success) 0 else 1)
    }

    private fun runViaDaemon(): Nothing {
        val (jdkMajor, javaBin) = pickToolchain()
        val client = DaemonLifecycle.ensureRunning(jdkMajor, javaBin)
        val (resolverMode, lockfilePath) = pickResolver()

        val exitCode = if (scriptArg == "-") {
            // `ktx run -` 语义：从 stdin 读脚本源码，inline 跑。
            // 注意 stdin 不会再当数据传给脚本（脚本读 stdin 此时拿不到，
            // 因为已被读光当源码用了）。这是 Phase 1 起就有的语义。
            val source = System.`in`.bufferedReader().readText()
            client.run(
                scriptPath = null,
                inlineSource = source,
                scriptArgs = scriptArgs,
                virtualName = "<stdin>",
                resolverMode = io.github.nebula.ktx.proto.v1.ResolverMode.NORMAL,
            )
        } else {
            val path = resolveScript(scriptArg)
            preflightToolchain(path)
            // stdin 透传：Phase 2.2 暂未实装。用 `--daemon` 跑会读 stdin 的脚本时，
            // daemon 那边的 System.in 是 /dev/null，readLine() 会得到 null。需要
            // 此功能的用户暂时去掉 --daemon 即可。Phase 2.3 加流式 stdin 透传。
            val stdinBytes: ByteArray? = null
            client.run(
                scriptPath = path,
                inlineSource = null,
                scriptArgs = scriptArgs,
                resolverMode = resolverMode,
                lockfilePath = lockfilePath,
                stdin = stdinBytes,
            )
        }
        exitProcess(exitCode)
    }

    /**
     * 把 CLI 选项 → daemon 协议 ResolverMode + 可选 lockfile 路径。
     */
    private fun pickResolver(): Pair<io.github.nebula.ktx.proto.v1.ResolverMode, Path?> {
        if (!frozen) return io.github.nebula.ktx.proto.v1.ResolverMode.NORMAL to null
        if (scriptArg == "-") error("stdin 模式不支持 --frozen（没有对应的 lockfile）")
        val path = resolveScript(scriptArg)
        val lockPath = Lockfile.pathFor(path)
        require(lockPath.exists()) {
            "frozen 模式需要 lockfile，但找不到：$lockPath。先运行 `ktx lock $scriptArg` 生成。"
        }
        return io.github.nebula.ktx.proto.v1.ResolverMode.FROZEN to lockPath
    }

    /**
     * 根据脚本声明的 `@file:Toolchain(jdk = ...)` 决定要哪个 JDK 主版本：
     *   - 脚本未声明 → 当前 JVM 主版本（不动 JDK，daemon 用启动 ktx 的那个）
     *   - 声明了但与当前一致 → 同上
     *   - 声明的版本不同 → 找已装 JDK；没装就装一个；用它的 java 启 daemon
     *
     * 返回 (主版本号, java 可执行文件路径或 null)。null 表示用当前 JVM。
     */
    private fun pickToolchain(): Pair<Int, String?> {
        val current = Runtime.version().feature()
        if (scriptArg == "-") return current to null  // stdin 模式无脚本可扫描

        val path = java.nio.file.Path.of(scriptArg).takeIf { it.toFile().isFile } ?: return current to null
        val tc = ToolchainHeaderScanner.scan(path)
        val requested = tc.jdk.takeIf { it.isNotEmpty() }?.substringBefore('.')?.toIntOrNull()
            ?: return current to null
        if (requested == current) return current to null

        val store = ToolchainStore()
        val install = store.find(requested) ?: run {
            log.info("脚本要求 JDK {}，本地未装，开始下载", requested)
            store.install(requested)
        }
        return requested to install.javaBin.toAbsolutePath().toString()
    }

    private fun runFile(runner: ScriptRunner): ResultWithDiagnostics<*> {
        val path = resolveScript(scriptArg)
        // 如果脚本要求一个不同的 JDK，下面这行会 re-exec 当前进程并不返回。
        // 没声明、或与当前 JVM 匹配，则继续。
        ToolchainDispatcher.dispatchIfNeeded(path)
        preflightToolchain(path)
        val args = scriptArgs.toTypedArray()

        return when {
            frozen -> {
                val lockPath = Lockfile.pathFor(path)
                val lockfile = Lockfile.read(lockPath)
                    ?: error("frozen 模式需要 lockfile，但找不到：$lockPath。先运行 `ktx lock $scriptArg` 生成。")
                log.info("frozen 模式：使用 lockfile {}（{} 个顶层依赖）", lockPath.fileName, lockfile.directs.size)
                runner.run(path, args, resolver = FrozenResolver(lockfile))
            }
            refreshLock -> {
                LockingFlow.lockAndOptionallyRun(
                    runner = runner,
                    scriptPath = path,
                    scriptArgs = args,
                    skipExecution = false,
                )
            }
            else -> runner.run(path, args)
        }
    }

    private fun resolveScript(arg: String): Path {
        val path = Path(arg)
        require(path.exists() && path.isRegularFile()) { "脚本不存在：$arg" }
        return path
    }

    /**
     * Phase 1.3：仅做工具链注解中 Kotlin 版本的「前置校验」。
     * `jdk` 字段已由 [ToolchainDispatcher] 在更早处理过（不匹配则 re-exec）。
     */
    private fun preflightToolchain(scriptPath: Path) {
        val tc = ToolchainHeaderScanner.scan(scriptPath)
        if (tc.kotlin.isNotEmpty() && tc.kotlin != BuildInfo.kotlinVersion) {
            throw IllegalStateException(
                "脚本声明 Kotlin ${tc.kotlin}，但当前 CLI 自带 ${BuildInfo.kotlinVersion}；" +
                    "多 Kotlin 版本支持将在 Phase 2 通过 daemon 路由提供。",
            )
        }
    }
}
