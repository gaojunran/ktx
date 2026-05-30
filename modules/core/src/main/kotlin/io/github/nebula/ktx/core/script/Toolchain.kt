package io.github.nebula.ktx.core.script

/**
 * 声明脚本期望的工具链（Kotlin 编译器版本与 JDK）。
 *
 * 这条注解 **不被** ScriptDefinition 的 refineConfiguration 实际消费 ——
 * CLI 在调编译器之前用纯文本扫描就读出了它的值，用来路由 daemon / 选 JDK。
 * 这里仅作为类型存在，使脚本里的 `@file:Toolchain(...)` 能通过编译。
 *
 * Phase 1 限制：
 *   - `kotlin` 字段若设置，必须等于 CLI 内置的 Kotlin 版本，否则报错；
 *     多 Kotlin 版本支持留到 Phase 2（依赖 daemon 路由）。
 *   - `jdk` 字段支持任意 LTS 主版本号字符串（如 "17"、"21"）。
 */
@Target(AnnotationTarget.FILE)
@Retention(AnnotationRetention.SOURCE)
annotation class Toolchain(
    val kotlin: String = "",
    val jdk: String = "",
)
