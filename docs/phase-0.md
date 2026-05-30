# Phase 0：可行性原型

> 时间：2026-05-30
> 主题：站在 `kotlin-main-kts` 肩上把 `.main.kts` 跑通

## 这一波要解决什么

立项时最大的不确定性是：**Kotlin 2.2.0 下嵌入式编译器还能不能用**。
2024 年 11 月 JetBrains 官方公告里宣布废弃 CLI REPL、JSR-223 等若干脚本相关接口，社区维护的 `kscript` 也已停摆一年多。如果连最基础的 `kotlin-scripting-jvm-host` + `kotlin-main-kts` 也跟着出问题，整个 ktx 项目就没必要做下去。

Phase 0 的目标只有一个：**用最少的代码证明嵌入式编译器仍然可用**，包括复杂场景下的依赖解析（`@file:DependsOn`）和参数透传。

## 关键事实（调研得到的）

| 项 | 结论 |
|---|---|
| `kotlin-scripting-jvm-host` 2.2.0 | Maven Central 已发布，与 Kotlin 2.2.0 同步 |
| `kotlin-main-kts` 2.2.0 | 同上，是官方继续维护的脚本宿主 |
| `MainKtsScript` 模板 | 包路径 `org.jetbrains.kotlin.mainKts`，可直接作为 ScriptDefinition |
| `@file:DependsOn` 依赖解析 | 内部用 **Eclipse Aether**（不是 Ivy） |
| 注解类位置 | `DependsOn` / `Repository` 在 `kotlin.script.experimental.dependencies`；`Import` / `CompilerOptions` 在 `org.jetbrains.kotlin.mainKts` |
| 缓存机制 | `MainKtsHostConfiguration` 读取 system property `kotlin.main.kts.compiled.scripts.cache.dir` 决定缓存目录 |

调研全程不动手写代码——先把"这一切到底是什么样"摸清楚再下笔，是 ktx 全项目的工作方式。

## 实现

代码极少，整个 spike 不到 60 行 Kotlin。

```kotlin
// modules/core/src/main/kotlin/.../exec/Phase0Spike.kt （已删除）
fun main(rawArgs: Array<String>) {
    val scriptFile = File(rawArgs[0])
    val compilationConfig = createJvmCompilationConfigurationFromTemplate<MainKtsScript>()
    val evaluationConfig = createJvmEvaluationConfigurationFromTemplate<MainKtsScript> {
        constructorArgs(rawArgs.drop(1).toTypedArray())
    }
    val result = BasicJvmScriptingHost().eval(
        scriptFile.toScriptSource(),
        compilationConfig,
        evaluationConfig,
    )
    // 打印诊断
}
```

直接复用官方 `MainKtsScript` 模板，所以 `@file:DependsOn` 等所有 main-kts 已经支持的注解开箱即用，**不用我们写一行解析代码**。

## 项目骨架

| 文件 | 作用 |
|---|---|
| `settings.gradle.kts` | multi-module，仅含 `:modules:core` |
| `build.gradle.kts` | 顶层 plugin 声明 |
| `gradle/libs.versions.toml` | 版本目录（kotlin / slf4j / logback 等） |
| `.mise.toml` | 锁 JDK 21 + Gradle 8.14 |
| `.gitignore` | 标准 Kotlin/Gradle 忽略 |

之所以 Phase 0 就用 multi-module，是因为 Phase 1+ 一定要拆 `cli` / `core` / `toolchain` / `daemon`，与其后期重排不如一开始就摆正。

## 验证结果

跑两个样例脚本：

```bash
mise exec -- ./gradlew :modules:core:run --args="samples/hello.main.kts foo bar"
mise exec -- ./gradlew :modules:core:run --args="samples/ktor-client.main.kts"
```

| 场景 | 实测 |
|---|---|
| 无依赖 hello.main.kts | 1.4 秒（含 JVM 启动 + 编译） |
| 含 ktor 客户端依赖（10+ 传递依赖），首次 | 37 秒（含联网下载 + 编译 + HTTP 请求） |
| 含 ktor 依赖，二次 | 10.8 秒（main-kts 默认未配缓存目录，仍重编） |

二次仍慢是预期内的：Phase 0 没动缓存目录，main-kts 默认 `~/.cache/main.kts.compiled.cache` 在某些场景下不生效。这正是 Phase 1.1 第一件事要解决的。

## 这一波撞到的坑

**JDK 26 跑不了 Gradle 8.14**。
本机 `java -version` 显示 26.0.1，Gradle 跑起来直接吐"26.0.1"作为错误信息。修法：`.mise.toml` 锁 JDK 21（Gradle 8.14 支持上限）。这个坑特别讽刺——ktx 立项的目的之一就是让用户**不用**再自己装对的 JDK 才能跑脚本。我们自己反而先吃了这个亏。

**Gradle 命令行工具按 PATH 找 java**。
即便 `mise.toml` 写了 `java = "temurin-21"`，没在当前 shell 激活 mise，`./gradlew` 仍走系统 java。所有 Gradle 调用都得用 `mise exec --` 前缀，README 里已写明。

**根 Gradle init 脚本注入了仓库**。
我最初在 `settings.gradle.kts` 写了 `repositoriesMode.set(FAIL_ON_PROJECT_REPOS)`，被你的 `~/.gradle/init.gradle.kts` 注入的镜像撞翻。改成 `PREFER_SETTINGS` 后不影响构建，仅留 warning。

## 总结

- 嵌入式编译器在 Kotlin 2.2.0 下完全可用，路径清晰。
- `kotlin-main-kts` 已经处理好了"`.main.kts` 该怎么跑"的所有脏活。我们只需要套个壳。
- Phase 1 可以放心地往这个 spike 上加 CLI、缓存目录化、lockfile、工具链管理等所有外围功能。
