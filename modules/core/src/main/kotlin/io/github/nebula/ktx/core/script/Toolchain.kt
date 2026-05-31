package io.github.nebula.ktx.core.script

/**
 * Declares the toolchain a script expects (Kotlin compiler version and JDK).
 *
 * This annotation is **not** consumed by ScriptDefinition's refineConfiguration ——
 * the CLI scans its values via plain text before invoking the compiler, in order
 * to route the daemon and select the JDK. It exists here purely as a type so
 * `@file:Toolchain(...)` in scripts compiles.
 *
 * Phase 1 limitations:
 *   - If `kotlin` is set, it must equal the Kotlin version bundled with the CLI;
 *     otherwise an error is raised. Multi-version Kotlin support is deferred to
 *     Phase 2 (which depends on daemon routing).
 *   - `jdk` accepts any LTS major version string (e.g. "17", "21").
 */
@Target(AnnotationTarget.FILE)
@Retention(AnnotationRetention.SOURCE)
annotation class Toolchain(
    val kotlin: String = "",
    val jdk: String = "",
)
