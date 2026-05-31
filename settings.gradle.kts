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
        // GitHub Packages, used only for dev.usage-spec:* (clikt-usage et al).
        // Auth via gradle properties or env vars; the includeGroup filter
        // prevents Gradle from probing this server for unrelated coordinates.
        // GitHub Packages requires a non-empty username but only the token
        // is actually validated, so we fall back to a placeholder when no
        // username variable is set.
        //
        // Variable lookup order: gradle properties → KTX_GH_* (used by
        // GitHub Actions in this repo, since the GITHUB_* prefix is
        // reserved by Actions and can't be set via workflow `env`)
        // → GITHUB_USERNAME / GITHUB_TOKEN (conventional names users export
        // locally or other CI systems set for free) → GITHUB_ACTOR
        // (auto-set inside Actions run-steps) → "ktx-build" placeholder.
        maven {
            name = "GitHubPackagesUsageIntegrations"
            url = uri("https://maven.pkg.github.com/gaojunran/usage-integrations")
            credentials {
                username = settings.providers.gradleProperty("githubUsername").orNull
                    ?: System.getenv("KTX_GH_USERNAME")
                    ?: System.getenv("GITHUB_USERNAME")
                    ?: System.getenv("GITHUB_ACTOR")
                    ?: "ktx-build"
                password = settings.providers.gradleProperty("githubToken").orNull
                    ?: System.getenv("KTX_GH_TOKEN")
                    ?: System.getenv("GITHUB_TOKEN")
            }
            content {
                includeGroup("dev.usage-spec")
            }
        }
        // JitPack hosts kdl4j (com.github.kdl-org:kdl4j), a transitive
        // dependency of usage-spec-kotlin's KDL renderer. Filter to that
        // single coordinate so other resolutions stay on Maven Central.
        maven {
            name = "JitPack"
            url = uri("https://jitpack.io")
            content {
                includeGroup("com.github.kdl-org")
            }
        }
    }
}

include(":modules:core")
include(":modules:cli")
include(":modules:toolchain")
include(":modules:protocol")
include(":modules:daemon")
