rootProject.name = "ktx"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Prefer settings-declared repositories but tolerate global init-script
    // injections (e.g. local mirrors).
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
    }
}

include(":modules:core")
include(":modules:cli")
include(":modules:toolchain")
include(":modules:protocol")
include(":modules:daemon")
