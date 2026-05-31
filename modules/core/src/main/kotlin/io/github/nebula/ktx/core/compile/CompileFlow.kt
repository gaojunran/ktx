package io.github.nebula.ktx.core.compile

import io.github.nebula.ktx.core.deps.LockingFlow
import io.github.nebula.ktx.core.deps.RecordingResolver
import io.github.nebula.ktx.core.exec.ScriptRunner
import io.github.nebula.ktx.core.script.KtsScript
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.jvm.util.KotlinJars

/**
 * 编译一个 .kts 脚本到独立 fat jar，不依赖 ktx 也不依赖 Kotlin 编译器。
 *
 * 工作流：
 *   1. 用 [RecordingResolver] 包裹真实 resolver，**仅编译**（不执行）脚本，
 *      抓取 main-kts 解析出的所有依赖 jar 路径；
 *   2. 编译产物落到独立的 build cache 目录（通过临时改 system property
 *      "kotlin.main.kts.compiled.scripts.cache.dir" 让 main-kts 写过去）；
 *   3. 用 [JarPacker] 把脚本产物 jar + 依赖 jar + ktx 必需的运行时 class
 *      合并成 fat jar。
 *
 * 产物 jar 不含：
 *   - kotlin-compiler-embeddable（脚本已编译完，不再需要编译器）
 *   - kotlin-scripting-* （运行时不需要 scripting host）
 *   - main-kts.jar（运行时不需要 MainKtsConfigurator）
 *   - ktx 自己的 cli/daemon/protocol 模块（运行时无关）
 *
 * 含的运行时 jar：
 *   - kotlin-stdlib + kotlin-script-runtime
 *   - 所有 @file:DependsOn 解析出的 jar
 *   - ktx core 模块的 jar（含 KtsScript 基类）
 */
class CompileFlow {

    private val log = LoggerFactory.getLogger("ktx.compile")

    fun compile(scriptPath: Path, outputJar: Path): ResultWithDiagnostics<*> {
        val absScriptPath = scriptPath.absolute().normalize()
        val tmpCacheDir = Files.createTempDirectory("ktx-compile-cache-")

        try {
            // 把 main-kts 缓存目录指到我们临时位置 —— 编译产物会落在这里、
            // 我们能精确定位（全局缓存里有很多 jar 不好挑）。
            val originalCache = System.getProperty(MAIN_KTS_CACHE_DIR_PROPERTY)
            System.setProperty(MAIN_KTS_CACHE_DIR_PROPERTY, tmpCacheDir.toAbsolutePath().toString())

            val runner = ScriptRunner(cacheDir = tmpCacheDir)
            val recording = RecordingResolver(LockingFlow.defaultRealResolver())

            log.info("编译 {}", absScriptPath)

            // 关键：**不**用 wrapForLockOnly。lock 流程把主体换成 Unit 是为了
            // 避免副作用，但 compile 必须保留脚本主体 —— 用户期望产物 jar 跑
            // 起来执行真实代码。所以走 runner.run（文件路径）让 main-kts 编译
            // 完整源码并产出 jar。
            //
            // 副作用问题：脚本主体确实会被执行一次（包含 HTTP 请求等）。这跟
            // ktx run 第一次跑没区别。代价可接受。Phase 2.5 可加一个「only
            // compile, never evaluate」的入口（main-kts 的 BasicJvmScriptingHost
            // 内部确有这种 API，但要绕开 RunnerKt 的默认评估器）。
            val compileResult = runner.run(absScriptPath, emptyArray(), resolver = recording)

            if (originalCache != null) System.setProperty(MAIN_KTS_CACHE_DIR_PROPERTY, originalCache)
            else System.clearProperty(MAIN_KTS_CACHE_DIR_PROPERTY)

            if (compileResult is ResultWithDiagnostics.Failure) {
                return compileResult
            }

            // 在 tmpCacheDir 里找新生成的 jar。可能有 0 个或 1 个 ——
            // 0 个：脚本太简单，main-kts 缓存机制对 inline 脚本不一定写盘
            //     （命中条件含「ScriptCompilationConfiguration 非瞬态项哈希」
            //      和「script source」，inline 模式下 source 没问题，但有些
            //      场景下缓存 key 算出来不写）。
            // 这一情况下需要 fallback：直接把 wrapped source 走完整 compile 流程。
            val jars = tmpCacheDir.toFile().listFiles { _, n -> n.endsWith(".jar") }.orEmpty()
            require(jars.size == 1) {
                "编译产物 jar 数量异常（cacheDir=$tmpCacheDir, found=${jars.size}）。" +
                    "这通常意味着 main-kts 缓存键算法变了，需要更新 ktx。"
            }
            val compiledJar = jars[0]

            val resolvedDeps = recording.snapshot().values.flatten().distinct().map { it.toPath() }
            val runtimeJars = collectRuntimeJars()
            val ktxCoreJar = locateKtxCoreJar()

            log.info(
                "合并：脚本产物({}KB) + {} 用户依赖 + {} Kotlin 运行时{}",
                compiledJar.length() / 1024,
                resolvedDeps.size,
                runtimeJars.size,
                if (ktxCoreJar != null) " + ktx core" else "",
            )

            val inputs = buildList {
                add(compiledJar.toPath())
                addAll(resolvedDeps)
                addAll(runtimeJars)
                if (ktxCoreJar != null) add(ktxCoreJar)
            }

            val mainClass = readMainClass(compiledJar.toPath())
            outputJar.parent?.createDirectories()
            JarPacker(mainClass).pack(inputs, outputJar)

            return ResultWithDiagnostics.Success(Unit, compileResult.reports)
        } finally {
            tmpCacheDir.toFile().deleteRecursively()
        }
    }

    /**
     * 运行时必需的 Kotlin jar：
     *   - kotlin-stdlib（基础语言库）
     *   - kotlin-script-runtime（脚本基类 / TemplateWithArgs 等）
     *   - kotlin-scripting-jvm（含 RunnerKt，脚本类的 main() 调用它来跑）
     *   - kotlin-scripting-common（RunnerKt 间接依赖：ScriptCompilationConfiguration 等）
     *   - kotlin-reflect（脚本类用 ClassReference.getObjectInstance 触发反射）
     *
     * 通过 [KotlinJars] 拿到 stdlib + script-runtime；其他几个通过
     * [Class.getProtectionDomain] 反向定位（这些 jar 已经在 ktx 自己的
     * classpath 里）。
     */
    private fun collectRuntimeJars(): List<Path> {
        val jars = mutableListOf<Path>()
        for (jar in KotlinJars.kotlinScriptStandardJars) {
            if (jar != null && jar.exists()) jars.add(jar.toPath())
        }
        // scripting-jvm 里的 RunnerKt 是脚本类 main() 直接调用的入口
        locateJarOf("kotlin.script.experimental.jvm.RunnerKt")?.let { jars.add(it) }
        // scripting-common 提供 ScriptCompilationConfiguration 等 API 类型
        locateJarOf("kotlin.script.experimental.api.ScriptCompilationConfiguration")?.let { jars.add(it) }
        // reflect：脚本类启动流程里 ConfigurationFromTemplateKt 用 KClass.objectInstance
        locateJarOf("kotlin.reflect.full.KClasses")?.let { jars.add(it) }
        return jars.distinct()
    }

    /**
     * 通过 ClassLoader 找到一个类所在的 jar 路径。失败返回 null。
     */
    private fun locateJarOf(className: String): Path? = runCatching {
        val cls = Class.forName(className)
        val src = cls.protectionDomain?.codeSource?.location ?: return null
        val path = Path.of(src.toURI())
        path.takeIf { it.toString().endsWith(".jar") && it.exists() }
    }.getOrNull()

    private fun locateKtxCoreJar(): Path? {
        val codeSource = KtsScript::class.java.protectionDomain?.codeSource?.location ?: return null
        val path = Path.of(codeSource.toURI())
        return path.takeIf { it.toString().endsWith(".jar") && it.exists() }
    }

    private fun readMainClass(jarPath: Path): String {
        java.util.jar.JarFile(jarPath.toFile()).use { jar ->
            val manifest = jar.manifest ?: error("$jarPath 无 manifest")
            return manifest.mainAttributes.getValue("Main-Class")
                ?: error("$jarPath manifest 无 Main-Class")
        }
    }

    companion object {
        private const val MAIN_KTS_CACHE_DIR_PROPERTY = "kotlin.main.kts.compiled.scripts.cache.dir"
    }
}
