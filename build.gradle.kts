plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

// Version is supplied by `-Pversion=...` from the release workflow (the tag
// without the leading `v`). Local builds and CI without an explicit override
// fall back to a SNAPSHOT marker so artifacts never accidentally collide
// with a real release.
val ktxVersion: String =
    (findProperty("version") as String?)?.takeIf { it.isNotBlank() && it != "unspecified" }
        ?: "0.0.0-SNAPSHOT"

allprojects {
    group = "io.github.nebula.ktx"
    version = ktxVersion
}
