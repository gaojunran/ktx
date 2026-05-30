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
import kotlin.script.experimental.dependencies.ExternalDependenciesResolver
import kotlin.script.experimental.dependencies.Repository
import kotlin.script.experimental.jvm.dependenciesFromClassContext
import kotlin.script.experimental.jvm.jvm

/**
 * ktx 自定义 ScriptDefinition。
 *
 * 设计原则是「复用而非 fork」：沿用官方 [MainKtsConfigurator] 处理
 * `@file:DependsOn` 等 main-kts 已经支持的注解，仅追加 `@file:Toolchain`
 * 的类型可见性（实际值由 CLI 端预扫描读出）。
 *
 * **可注入 resolver**：通过 [resolverOverride] ThreadLocal 实现。`@KotlinScript`
 * 注解要求 compilationConfiguration class 是 0 参可实例化的，没法通过构造
 * 器传 resolver。我们用 ThreadLocal 旁路：调用方在执行 `BasicJvmScriptingHost.eval`
 * 之前 set 当前线程的 resolver；[KtsScriptDefinition] 实例化时读它，得到
 * 注入的版本。
 *
 * 这种 ThreadLocal 模式不优雅，但在脚本编译这种「单线程、单脚本一个 host」
 * 的场景下完全够用。Phase 2 上 daemon 后每个请求一个 worker 线程，仍然
 * 适用。
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
            // 都可见。
            dependenciesFromClassContext(
                KtsScriptDefinition::class,
                wholeClasspath = true,
            )
        }
        refineConfiguration {
            val resolver = resolverOverride.get()
            val configurator =
                if (resolver != null) MainKtsConfigurator(resolver) else MainKtsConfigurator()
            onAnnotations(
                DependsOn::class,
                Repository::class,
                Import::class,
                CompilerOptions::class,
                handler = configurator,
            )
            // Toolchain 由 CLI 预扫描消费，编译期不需要 handler。
        }
        ide {
            acceptedLocations(ScriptAcceptedLocation.Everywhere)
        }
    },
)

/**
 * 调用方在 `BasicJvmScriptingHost().eval(...)` 之前 set 这里，可让
 * [KtsScriptDefinition] 用注入的 resolver 替代默认的 `Compound(FileSystem, Maven)`。
 *
 * 用 [withResolverOverride] 包装，自动负责 set/clear。
 */
@PublishedApi
internal val resolverOverride: ThreadLocal<ExternalDependenciesResolver?> = ThreadLocal.withInitial { null }

inline fun <T> withResolverOverride(resolver: ExternalDependenciesResolver?, block: () -> T): T {
    val prev = resolverOverride.get()
    resolverOverride.set(resolver)
    try {
        return block()
    } finally {
        resolverOverride.set(prev)
    }
}
