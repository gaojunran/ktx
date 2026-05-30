package io.github.nebula.ktx.core.deps

import io.github.nebula.ktx.core.exec.ScriptRunner
import io.github.nebula.ktx.core.exec.printReports
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.readBytes
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.dependencies.CompoundDependenciesResolver
import kotlin.script.experimental.dependencies.FileSystemDependenciesResolver
import kotlin.script.experimental.dependencies.maven.MavenDependenciesResolver

/**
 * 把「正常 run + 旁路记录解析结果」与「写 lockfile」组合成一个动作。
 *
 * 是 `ktx lock` 与 `ktx run`（生成/刷新 lockfile）共享的工作流。
 */
object LockingFlow {

    private val log = LoggerFactory.getLogger("ktx.lock")

    /**
     * 跑一遍 [scriptPath]，过程中把 main-kts 的依赖解析旁路记录下来，结束后
     * 把记录写到 `<scriptPath>.lock`。
     *
     * @param runner 复用 CLI 创建的 ScriptRunner（保持缓存目录一致）。
     * @param skipExecution 仅做编译（含依赖解析），不真正执行脚本主体。
     *                     `kts lock` 用 true：用户只想刷新 lockfile，没必要
     *                     真的发 HTTP 请求。
     */
    fun lockAndOptionallyRun(
        runner: ScriptRunner,
        scriptPath: Path,
        scriptArgs: Array<String>,
        skipExecution: Boolean,
    ): ResultWithDiagnostics<EvaluationResult> {
        val recording = RecordingResolver(defaultRealResolver())
        val finalPath = scriptPath.toAbsolutePath().normalize()
        val source = finalPath.readBytes()
        val scriptHash = sha256Hex(source)

        // skipExecution 模式：把脚本主体替换成 `Unit`，仅保留 `@file:` 头部
        // 注解触发 main-kts 的依赖解析；同时为了**绕过编译产物缓存**（缓存
        // 命中时 main-kts 不再调 resolver，RecordingResolver 拿不到记录），
        // 用一个临时 cache dir 跑这一次。
        //
        // 这是 `kts lock` 的核心折中：lock 操作本身要付一次完整编译时间，
        // 换来精确的依赖记录与零脚本副作用。后续 `kts run --frozen` 命中
        // 主缓存目录的产物，照常秒过。
        val result = if (skipExecution) {
            val wrapped = wrapForLockOnly(source.decodeToString())
            runner.runInline(
                wrapped,
                scriptArgs,
                virtualName = finalPath.fileName.toString(),
                resolver = recording,
                bypassCompiledCache = true,
            )
        } else {
            runner.run(finalPath, scriptArgs, resolver = recording)
        }

        // 不论编译成功失败都尝试写 lockfile：失败时也许只解析到了一部分依赖，
        // 但部分胜过没有；用户可以再跑一次。
        val directs = recording.snapshot().mapValues { (_, files) ->
            files.mapNotNull { f -> FrozenResolver.toM2Relative(f).also { rel ->
                if (rel == null) log.warn("依赖 jar 不在 ~/.m2/repository 之下，lockfile 跳过：{}", f.absolutePath)
            } }
        }.filterValues { it.isNotEmpty() }

        if (directs.isNotEmpty()) {
            val lockfile = Lockfile(
                scriptHash = scriptHash,
                directs = directs,
                repositories = recording.repositorySnapshot(),
            )
            val lockPath = Lockfile.pathFor(finalPath)
            lockfile.write(lockPath)
            log.info("已写入 lockfile：{}（{} 个顶层依赖）", lockPath, directs.size)
        } else {
            log.info("脚本无 Maven 依赖，跳过 lockfile 写入")
        }

        return result
    }

    /**
     * 把脚本头部注解保留下来，但把脚本主体替换成 no-op，用于 `kts lock`。
     *
     * 思路：找到「脚本主体开始」的位置（ToolchainHeaderScanner 的等价逻辑），
     * 把后面的代码替换成 `Unit`。这样 main-kts 仍会走依赖解析，但不会执行
     * 脚本副作用（如发 HTTP 请求）。
     *
     * Phase 1.2 用最朴素的实现：找第一个非 `#!` / 非空 / 非 `//` / 非 `@file:`
     * 的行，从那里截断换成 `Unit`。失败时回退到不截断（让脚本完整执行，慢
     * 但安全）。
     */
    private fun wrapForLockOnly(source: String): String {
        val lines = source.lines()
        var headEnd = lines.size
        for ((idx, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#!")) continue
            if (line.startsWith("//")) continue
            if (line.startsWith("@file:")) continue
            // 块注释、import 等都视作脚本主体开始。
            headEnd = idx
            break
        }
        return lines.take(headEnd).joinToString("\n") + "\nUnit\n"
    }

    /**
     * 重建 main-kts 默认 resolver 链：`Compound(FileSystem, Maven)`。
     * 我们必须自己拼，因为 [MainKtsConfigurator] 把默认 resolver 私有化了。
     */
    fun defaultRealResolver(): CompoundDependenciesResolver =
        CompoundDependenciesResolver(FileSystemDependenciesResolver(), MavenDependenciesResolver())

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/** 兼容辅助：把 ResultWithDiagnostics 直接打印一遍诊断。 */
fun ResultWithDiagnostics<EvaluationResult>.printAll() = printReports()
