package io.github.nebula.ktx.cli.command

/**
 * 编译期注入的版本元信息。
 *
 * Phase 1 只用 hardcode 字符串，等 Phase 1 后期（AppCDS / distribution）做
 * 打包脚本时再换成 BuildConfig 生成。当前与 libs.versions.toml 中的 kotlin
 * 版本必须一致。
 */
object BuildInfo {
    const val kotlinVersion: String = "2.2.0"
}
