package io.github.nebula.ktx.cli

import io.github.nebula.ktx.cli.meta.ScriptToolchain
import io.github.nebula.ktx.cli.meta.ToolchainHeaderScanner
import io.github.nebula.ktx.toolchain.JdkInstall
import io.github.nebula.ktx.toolchain.ToolchainStore
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * 处理脚本声明的 `@file:Toolchain(jdk = "...")`：
 *
 *   1. 读出脚本声明的 JDK 主版本；
 *   2. 与当前 JVM 主版本对比；不匹配则确保对应 JDK 已装（按需自动下载）；
 *   3. 通过 re-exec ktx 启动脚本（带 JAVA_HOME）切换到目标 JVM，原 args 透传。
 *
 * **避免无限递归**：环境变量 `KTX_TOOLCHAIN_DISPATCHED=1` 标记一次 re-exec
 * 已发生。子进程读到此变量就跳过路由，直接以当前 JVM 跑下去 ——
 * 即便子进程的 JVM 版本与脚本声明的对不上，也不会再切（说明上一层路由
 * 选错或安装失败，应当报告，不应再尝试）。
 */
object ToolchainDispatcher {

    private val log = LoggerFactory.getLogger("ktx.cli.dispatch")

    private const val DISPATCH_ENV = "KTX_TOOLCHAIN_DISPATCHED"

    /**
     * 如果脚本要求一个 ktx 不在它上面运行的 JDK，**直接以那个 JDK re-exec
     * 当前进程并永不返回**。否则返回（让 RunCommand 继续走正常逻辑）。
     *
     * @param scriptPath 脚本路径，用于扫描 @file:Toolchain。
     * @param store 注入便于测试。
     */
    fun dispatchIfNeeded(scriptPath: Path, store: ToolchainStore = ToolchainStore()) {
        if (System.getenv(DISPATCH_ENV) != null) {
            log.debug("已是 dispatched 子进程，跳过 toolchain 路由")
            return
        }

        val tc = ToolchainHeaderScanner.scan(scriptPath)
        val majorRequested = parseMajor(tc) ?: return  // 脚本未声明 jdk
        val majorCurrent = currentJvmMajor()

        if (majorRequested == majorCurrent) {
            log.debug("当前 JVM 主版本 {} 已匹配脚本声明，无需切换", majorCurrent)
            return
        }

        val install = store.find(majorRequested) ?: run {
            log.info("脚本要求 JDK {}，本地未装，开始下载", majorRequested)
            store.install(majorRequested)
        }

        log.info("切换到 JDK {} ({})", majorRequested, install.javaHome)
        reExec(install)
    }

    private fun parseMajor(tc: ScriptToolchain): Int? =
        tc.jdk.takeIf { it.isNotEmpty() }
            ?.let { it.substringBefore('.').toIntOrNull() }
            ?: run {
                if (tc.jdk.isNotEmpty()) {
                    log.warn("无法解析 @file:Toolchain(jdk = \"{}\") 中的主版本号，忽略", tc.jdk)
                }
                null
            }

    /**
     * 当前 JVM 主版本。`Runtime.version().feature()` 在 JDK 9+ 总是返回主
     * 版本号（21、17 等），不需要解析字符串。
     */
    private fun currentJvmMajor(): Int = Runtime.version().feature()

    /**
     * 用 [install] 重启 ktx，**不返回**。
     *
     * 实现：找到当前 ktx 启动脚本路径（环境变量 / sun 属性 / 启发式），
     * 用 [ProcessBuilder] 启子进程，inheritIO，等子进程退出后用同样的 exit
     * code 退出本进程。
     *
     * 不用 `execve()`：JVM 进程内调 `execve` 需要 JNI；而且 JVM 退出可能
     * 阻塞在 shutdown hooks 上。fork + 等待是更可移植的做法，代价是多一
     * 个进程的内存（短暂存在直到子进程结束）。
     */
    private fun reExec(install: JdkInstall): Nothing {
        val launcher = locateLauncherScript()
            ?: error(
                "无法定位 ktx 启动脚本以重新分派到 JDK ${install.majorVersion}。" +
                    "请确保 ktx 通过 installDist 生成的 bin/ktx 启动，而不是直接 java -jar。",
            )

        // 透传原始 argv：JVM main(args) 能拿到 args，但拿不到当前进程的脚本路径。
        // 我们用 ProcessHandle.current().info().arguments() 拿到原始 argv（含
        // launcher 之后的部分），优雅但 macOS 上有时返回空 —— 退化用 ktx 自己
        // 内存里保存的 originalArgs（Main.kt 启动时存）。
        val args = OriginalArgs.value()
            ?: error("未捕获到原始 argv（OriginalArgs.set 未在 Main 中调用？）")

        val cmd = listOf(launcher.toAbsolutePath().toString()) + args
        val pb = ProcessBuilder(cmd)
            .redirectErrorStream(false)
            .redirectInput(ProcessBuilder.Redirect.INHERIT)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
        pb.environment()["JAVA_HOME"] = install.javaHome.toAbsolutePath().toString()
        pb.environment()[DISPATCH_ENV] = "1"

        log.debug("re-exec：{}", cmd.joinToString(" "))
        val proc = pb.start()
        val exitCode = proc.waitFor()
        kotlin.system.exitProcess(exitCode)
    }

    /**
     * 找 `bin/ktx` 启动脚本路径。优先级：
     *   1. 环境变量 `KTX_LAUNCHER`（开发期或将来 daemon 自报）；
     *   2. `org.gradle.appname` 系统属性 + 可执行文件位置启发式；
     *   3. 通过 `Class.getProtectionDomain().codeSource.location` 找到 jar，
     *      回溯到 install root，找 `bin/ktx`。
     */
    private fun locateLauncherScript(): Path? {
        System.getenv("KTX_LAUNCHER")?.takeIf { it.isNotBlank() }?.let { return Path.of(it) }

        // Gradle Application 启动脚本会 set APP_HOME 环境变量
        System.getenv("APP_HOME")?.takeIf { it.isNotBlank() }?.let {
            val candidate = Path.of(it, "bin", "ktx")
            if (candidate.toFile().exists()) return candidate
        }

        // 通过 jar 路径回溯：lib/cli-*.jar 的祖父目录就是 install root
        val codeSource = ToolchainDispatcher::class.java.protectionDomain?.codeSource?.location
            ?: return null
        val jarFile = Path.of(codeSource.toURI())
        val installRoot = jarFile.parent?.parent ?: return null
        val candidate = installRoot.resolve("bin/ktx")
        return if (candidate.toFile().exists()) candidate else null
    }
}

/**
 * 在进程入口（[Main.main]）调用 [set] 保存原始 argv 副本，给后续 re-exec
 * 用。比 `ProcessHandle.current().info().arguments()` 可靠（后者在 macOS
 * 上常返回空）。
 */
internal object OriginalArgs {
    private var args: Array<String>? = null
    fun set(args: Array<String>) {
        this.args = args
    }

    fun value(): List<String>? = args?.toList()
}
