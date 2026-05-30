package io.github.nebula.ktx.core.script

import org.jetbrains.kotlin.mainKts.CompilerOptions
import org.jetbrains.kotlin.mainKts.Import
import org.jetbrains.kotlin.mainKts.MainKtsConfigurator
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptAcceptedLocation
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.acceptedLocations
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.ide
import kotlin.script.experimental.api.refineConfiguration
import kotlin.script.experimental.dependencies.DependsOn
import kotlin.script.experimental.dependencies.Repository
import kotlin.script.experimental.jvm.dependenciesFromClassContext
import kotlin.script.experimental.jvm.jvm

/**
 * ktx 自定义 ScriptDefinition。
 *
 * 设计原则是「复用而非 fork」：
 *   - 沿用官方 [MainKtsConfigurator] 处理 `@file:DependsOn` 等所有
 *     已被生态广泛使用的注解；
 *   - 仅追加 ktx 自有的 `@file:Toolchain` 类型可见性（实际值在 CLI
 *     端预扫描读出，这里不做配置变更）。
 *
 * 之所以单独定义一个 ScriptDefinition 而不是直接用 [MainKtsScript]：
 *   - 我们要在脚本 classpath 里暴露 [Toolchain]，main-kts 不知道它；
 *   - 后续 Phase 1 还要往这里塞缓存目录 fingerprint、lockfile 模式标记
 *     等 ktx 专属配置项，复用 ScriptCompilationConfiguration 的键值结构
 *     比改 main-kts 干净得多。
 */
@KotlinScript(
    fileExtension = "kts",
    compilationConfiguration = KtsScriptDefinition::class,
)
abstract class KtsScript(val args: Array<String>)

class KtsScriptDefinition : ScriptCompilationConfiguration(
    {
        defaultImports(
            DependsOn::class,
            Repository::class,
            Import::class,
            CompilerOptions::class,
            Toolchain::class,
        )
        jvm {
            // wholeClasspath = true：把 KtsScriptDefinition 所在 classloader
            // 看到的全部 jar 都暴露给脚本，确保 main-kts 注解类与 Toolchain
            // 都可见。Phase 1 不做 classpath 最小化，避免和 main-kts 内部
            // dependenciesFromClassContext 的 jar 名匹配逻辑搏斗。
            dependenciesFromClassContext(
                KtsScriptDefinition::class,
                wholeClasspath = true,
            )
        }
        refineConfiguration {
            onAnnotations(
                DependsOn::class,
                Repository::class,
                Import::class,
                CompilerOptions::class,
                handler = MainKtsConfigurator(),
            )
            // Toolchain 由 CLI 预扫描消费，编译期不需要再处理；
            // 也不必注册一个空的 handler，未注册 = 不调用，零成本。
        }
        ide {
            acceptedLocations(ScriptAcceptedLocation.Everywhere)
        }
    },
)
