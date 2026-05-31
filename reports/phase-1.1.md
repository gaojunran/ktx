# Phase 1.1：CLI 主干 + `@file:Toolchain` + 编译缓存目录化

> 时间：2026-05-30 ~ 31
> 主题：把 Phase 0 的 spike 变成可被人类用的 `ktx` 命令

## 这一波要解决什么

Phase 0 证明"能跑"，Phase 1.1 要让它"好用"。具体三件事：

1. **CLI 表面**：`ktx run` / `ktx -e` / `ktx run -`（stdin）这套和 uv / bun 同款的最小命令集。
2. **`@file:Toolchain` 注解**：脚本可以声明自己需要的 Kotlin 编译器版本与 JDK 主版本，CLI 在调编译器前就读出来，用于后续路由。
3. **编译缓存目录化**：把 main-kts 的编译产物缓存指到 `~/.cache/ktx/compiled/`，让二次启动快起来。

不在这一波的：lockfile、`kts add`、`kts toolchain install`、AppCDS。这些是 Phase 1.2 / 1.3 / 1.4。

## 设计决策

### 1. 自定义 ScriptDefinition，复用 main-kts 的注解处理器

**为什么不直接用 `MainKtsScript`**？因为我们要往脚本 classpath 里加 `Toolchain` 注解类。`MainKtsScriptDefinition` 写死了它的 `defaultImports`，没办法叠加。

**为什么不 fork 整个 main-kts**？因为 `MainKtsConfigurator` 内部那套 Aether resolver 配置又长又稳，重写没收益。

折中：**自己定义 `KtsScriptDefinition`，但把注解处理 handler 直接复用 `MainKtsConfigurator`**。

```kotlin
@KotlinScript(
    fileExtension = "kts",
    compilationConfiguration = KtsScriptDefinition::class,
)
abstract class KtsScript(val args: Array<String>)

class KtsScriptDefinition : ScriptCompilationConfiguration({
    defaultImports(DependsOn::class, Repository::class, Import::class, CompilerOptions::class, Toolchain::class)
    jvm {
        dependenciesFromClassContext(KtsScriptDefinition::class, wholeClasspath = true)
    }
    refineConfiguration {
        onAnnotations(
            DependsOn::class, Repository::class, Import::class, CompilerOptions::class,
            handler = MainKtsConfigurator(),  // 直接拿来用
        )
        // Toolchain 由 CLI 预扫描消费，编译期不需要 handler
    }
    ide { acceptedLocations(ScriptAcceptedLocation.Everywhere) }
})
```

注意 `wholeClasspath = true`：把 ktx 自己运行时的全部 classpath 暴露给脚本。Phase 1 不优化 classpath 体积，避免和 main-kts 内部 jar 名匹配逻辑搏斗——脚本能用、依赖解析正常就行。

### 2. `@file:Toolchain` 必须在编译器加载前读出

注解的值决定了用哪个 Kotlin 编译器和 JDK 来编译这个脚本本身。这是先有鸡还是先有蛋的问题：**靠注解处理 handler 来读它就太晚了**。

解法：**CLI 端用纯文本扫描脚本头部**。`ToolchainHeaderScanner` 不依赖任何 Kotlin 编译器组件，它只做这几件事：

- 跳过 shebang（`#!`）
- 跳过空行、`//` 行注释、`/* ... */` 块注释（含跨行）
- 命中 `@file:Toolchain(...)` 时按 `\w+ = "..."` 提取参数
- 遇到第一个非空、非注释、非 `@file:` 行就停（说明脚本正文开始了）

故意不写完整 Kotlin 解析器：失败时回退到「未声明 Toolchain」即可，编译器自己还会再校验一次。**鲁棒但不完美** 的策略，6 个单测覆盖关键边界（shebang、混合注解、注释、多行 Toolchain、head 区终止）。

```kotlin
@file:Toolchain(
    kotlin = "2.2.0",
    jdk = "21",
)
```

### 3. CLI 默认子命令：`ktx foo.kts` 等价 `ktx run foo.kts`

clikt 5.x 没有官方"默认子命令"机制。我没去 hack `currentContext.invokedSubcommand`（那个属性是只读的，搜索结果说能写是错的）。

更简单的做法：**在交给 clikt 之前先重写 argv**。

```kotlin
fun main(rawArgs: Array<String>) {
    val args = normalizeArgs(rawArgs)
    KtxCommand().subcommands(RunCommand(), EvalCommand()).main(args)
}

private fun normalizeArgs(args: Array<String>): Array<String> {
    if (args.isEmpty()) return args
    val first = args[0]
    if (first == "-e" || first == "--expr") return arrayOf("eval") + args
    if (first == "-") return arrayOf("run") + args
    if (first.startsWith("-")) return args
    if (first in setOf("run", "eval")) return args
    if (java.io.File(first).isFile) return arrayOf("run") + args
    return args
}
```

判断「是不是脚本路径」用 `File.isFile()`：保守、不影响未来加新子命令。

### 4. 缓存目录通过 system property 注入

`MainKtsHostConfiguration` 读这个属性来决定缓存目录：

```kotlin
System.setProperty("kotlin.main.kts.compiled.scripts.cache.dir", "/path/to/cache")
```

我们在 `ScriptRunner.init` 里 set 一次，路径按 XDG 规范：

- 优先 `$XDG_CACHE_HOME/ktx/compiled/`
- 回退 `$HOME/.cache/ktx/compiled/`

注意一定要用 `MainKtsHostConfiguration`（不是默认的 `defaultJvmScriptingHostConfiguration`），不然这个属性根本没人读。

## 项目布局变化

```diff
  modules/
    core/
      src/main/kotlin/io/github/nebula/ktx/core/
+       script/
+         KtsScript.kt              # 自定义 ScriptDefinition
+         Toolchain.kt              # @file:Toolchain 注解
        exec/
-         Phase0Spike.kt            # 已删除
+         ScriptRunner.kt           # 包装 BasicJvmScriptingHost
+   cli/                            # 新模块
+     src/main/kotlin/io/github/nebula/ktx/cli/
+       Main.kt
+       command/
+         BuildInfo.kt              # 编译期版本元信息
+         RunCommand.kt
+         EvalCommand.kt
+       meta/
+         ToolchainHeaderScanner.kt
+     src/test/kotlin/.../meta/
+       ToolchainHeaderScannerTest.kt   # 6 个单测
+     src/main/resources/logback.xml
```

Gradle Application 插件直接把 CLI 打成 `modules/cli/build/install/ktx/bin/ktx` 可执行脚本——开发期直接用它跑，不必每次 `./gradlew :modules:cli:run`。

## 验证结果

```
$ KTX=$(pwd)/modules/cli/build/install/ktx/bin/ktx
$ $KTX --help
Usage: ktx [<options>] <command> [<args>]...
Commands:
  run
  eval

$ time $KTX samples/hello.main.kts foo bar
hello from main.kts
args (2):
  [0] foo
  [1] bar
        1.54 real         3.11 user         0.11 sys

$ time $KTX run samples/hello.main.kts baz       # 第二次，缓存命中
hello from main.kts
args (1):
  [0] baz
        0.21 real         0.34 user         0.02 sys

$ time $KTX -e 'println(2 + 3)'
5
        1.41 real         2.92 user         0.09 sys

$ echo 'println("hello from stdin")' | $KTX run -
hello from stdin
```

**关键观察**：

| 场景 | 用时 |
|---|---|
| `hello.main.kts` 首次 | 1.54s |
| `hello.main.kts` 缓存命中 | **220ms** |
| `-e` 任意表达式（每次源码不同） | 1.4s |
| `ktor-client.main.kts` 二次 | 8.2s（依赖解析未跳过） |

**缓存命中的 220ms** 已经接近 bun 量级，证明 main-kts 缓存机制配合正确目录工作很好。
ktor 二次 8 秒说明 Phase 1.2 的 lockfile 必要：编译产物缓存命中了，但 main-kts 仍走 Aether refineConfiguration 来确认依赖位置——网络差时这是大头。

## 这一波撞到的坑

**`Import` / `CompilerOptions` 不在 `kotlin.script.experimental.api` 包里。**
Web 搜索结果给我误导，告诉我这五个注解类都在 stdlib API 包里。实际去 `kotlin-main-kts.jar` 里翻：`Import` 和 `CompilerOptions` 是 main-kts 自己的（`org.jetbrains.kotlin.mainKts`），只有 `DependsOn` / `Repository` 在 stdlib。这个区别在 main-kts 源码顶部用 wildcard import 隐藏了，光看 web 出来的代码片段会以为它们在同一个包。

**KDoc 里写 `/* ... */` 会让 Kotlin 编译器错乱。**
我在 `ToolchainHeaderScanner` 的 KDoc 里直接写"移除 `/* ... */`"，Kotlin 把 `/*` 当作嵌套块注释开始，整个文件后面全部被吃掉。改成自然语言描述（"slash-star 到 star-slash"）就好了。

**Gradle Application 任务的 cwd 不是工程根。**
默认是模块目录，导致 `--args="samples/foo.kts"` 找不到文件。fix：在 `tasks.named<JavaExec>("run") { workingDir = rootProject.projectDir }`。

**clikt 5.x 子命令必须用 `subcommands(...)`，不要乱 import。**
有 `CoreCliktCommand` 这种偏精简的基类，但混着用会带来诡异的 import 找不到 `main()` 扩展函数。Phase 1.1 用最稳的 `CliktCommand`，等真用上 mordant 渲染再考虑 minimal 路线。

## 已知遗留 / 下一波要做的

1. **依赖解析没缓存**。ktor 样例二次仍 8 秒，主要是 Aether 重连。Phase 1.2 出 lockfile 解决。
2. **`@file:Toolchain` 的 `jdk` 字段只打日志**，没真用——等 Phase 1.3 `kts toolchain install` 上线后接通。
3. **`@file:Toolchain` 的 `kotlin` 字段只能等于 CLI 自带版本**，否则报错。多版本支持要靠 daemon 路由（Phase 2）。
4. **CLI 启动开销 ~200ms**，Phase 1.4 上 AppCDS 后能压到 ~80ms。
5. **没有 `kts cache info / clean` 子命令**，下一波随手加。

## 总结

CLI 主干跑起来了，二次启动从 1.5s 降到 220ms。这一波的关键不是写多少代码，而是几次"调研把事情先弄清楚"避免了大量返工：

- `kotlin-main-kts` 哪些类可以直接用、哪些注解处理 handler 可以原样套——一次看清就避免了 fork 整个项目。
- `MainKtsHostConfiguration` 读 system property 的细节——一次摸清就避免了"换缓存目录"变成"重写整个 host"。
- clikt 5.x 没有"默认子命令" API——一次确认就避免了去 hack 私有钩子，改用 argv 重写。

Phase 1.2 接着做 lockfile。
