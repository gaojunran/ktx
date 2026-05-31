package io.github.nebula.ktx.cli.command

/**
 * Compile-time-injected version metadata.
 *
 * Phase 1 hardcodes the string; we will switch to a generated BuildConfig
 * later in Phase 1 when the packaging scripts (AppCDS / distribution) land.
 * For now this must stay in sync with the kotlin version in libs.versions.toml.
 */
object BuildInfo {
    const val kotlinVersion: String = "2.2.0"
}
