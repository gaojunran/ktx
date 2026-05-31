package io.github.nebula.ktx.core.script

import org.jetbrains.kotlin.mainKts.CompilerOptions
import org.jetbrains.kotlin.mainKts.Import
import org.jetbrains.kotlin.mainKts.MainKtsConfigurator
import org.jetbrains.kotlin.mainKts.configureConstructorArgsFromMainArgs
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptAcceptedLocation
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.acceptedLocations
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.ide
import kotlin.script.experimental.api.refineConfiguration
import kotlin.script.experimental.api.refineConfigurationBeforeEvaluate
import kotlin.script.experimental.dependencies.DependsOn
import kotlin.script.experimental.dependencies.ExternalDependenciesResolver
import kotlin.script.experimental.dependencies.Repository
import kotlin.script.experimental.jvm.dependenciesFromClassContext
import kotlin.script.experimental.jvm.jvm

/**
 * ktx's custom ScriptDefinition.
 *
 * Design principle: "reuse, don't fork". The official [MainKtsConfigurator]
 * handles `@file:DependsOn` and other annotations already supported by main-kts;
 * we only add type visibility for `@file:Toolchain` (whose value is read out by
 * the CLI's pre-scan).
 *
 * **Injectable resolver**: implemented via the [resolverOverride] ThreadLocal.
 * `@KotlinScript` requires compilationConfiguration to be a zero-arg-constructible
 * class, so we cannot pass a resolver through its constructor. We use a ThreadLocal
 * side channel: callers set the resolver on the current thread before invoking
 * `BasicJvmScriptingHost.eval`; [KtsScriptDefinition] reads it on instantiation
 * and gets the injected version.
 *
 * This ThreadLocal pattern isn't elegant, but for script compilation
 * (single-threaded, one host per script) it's perfectly sufficient.
 *
 * **Why an EvaluationConfiguration is required (Phase 2.4)**:
 *   - In `ktx run`, ScriptRunner explicitly calls
 *     createJvmEvaluationConfigurationFromTemplate + constructorArgs(scriptArgs),
 *     so KtsScript(args) gets the right arguments.
 *   - In `ktx compile`, the output jar runs through RunnerKt's default entry point,
 *     so the evaluation config must be baked into the .class at compile time.
 *     This means @KotlinScript **must** declare
 *     evaluationConfiguration = KtsEvaluationConfiguration::class, which uses
 *     [configureConstructorArgsFromMainArgs] to wire main(String[])'s args into
 *     the constructor. Otherwise the produced jar fails with "wrong number of
 *     arguments" at runtime.
 */
@KotlinScript(
    fileExtension = "kts",
    compilationConfiguration = KtsScriptDefinition::class,
    evaluationConfiguration = KtsEvaluationConfiguration::class,
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
            // wholeClasspath = true: expose every jar visible to the
            // KtsScriptDefinition classloader to the script, so main-kts
            // annotation classes and Toolchain are all reachable.
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
            // Toolchain is consumed by the CLI's pre-scan; no compile-time handler needed.
        }
        ide {
            acceptedLocations(ScriptAcceptedLocation.Everywhere)
        }
    },
)

/**
 * Evaluation configuration: wires KtsScript's `args` constructor parameter
 * automatically from main(String[]).
 *
 * Since Phase 2.4: declaring this evaluationConfiguration is the prerequisite
 * for `ktx compile` output jars to run directly. The KJvmCompiledScript embedded
 * in the produced .class references this class, and when RunnerKt calls
 * createEvaluationConfigurationFromTemplate, its hook converts mainArguments
 * into constructorArgs.
 */
class KtsEvaluationConfiguration : ScriptEvaluationConfiguration(
    {
        refineConfigurationBeforeEvaluate(::configureConstructorArgsFromMainArgs)
    },
)

/**
 * Callers can set this before `BasicJvmScriptingHost().eval(...)` to make
 * [KtsScriptDefinition] use the injected resolver in place of the default
 * `Compound(FileSystem, Maven)`.
 *
 * Wrap with [withResolverOverride] to handle set/clear automatically.
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
