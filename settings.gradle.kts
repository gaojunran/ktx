rootProject.name = "ktx"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // 仅声明优先使用 settings 中的仓库，但允许全局 init 脚本（如本地镜像）注入。
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
    }
}

include(":modules:core")
include(":modules:cli")
