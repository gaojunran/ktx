# Phase 4.1：ktx Shell DSL —— 对标 dax 的 Kotlin 脚本级 shell 能力

> 时间：2026-06-01
>
> 目标：为 ktx 脚本提供一套地道 Kotlin 风格的 shell 执行 DSL，功能对齐 dax（进程执行、参数安全、管道、终端 UI、跨平台 shell），但语法更贴合 Kotlin 习惯。

## 背景与动机

ktx 目前只负责「编译并运行 Kotlin 脚本」，脚本内部执行外部命令没有标准方式。用户要么手写 `ProcessBuilder`（冗长），要么引入第三方库（没有明显赢家）。dax 证明了「脚本语言内置 shell DSL」这个方向的价值，但它是为 TypeScript 设计的：tagged template literal、Prototype-based builder、JavaScript 的异步模型。Kotlin 有更丰富的语法工具（扩展函数、中缀操作符、具名参数、带接收者的 lambda、Duration 类型），完全有能力做得更简洁。

## 架构决策

### 独立库 + ktx 默认内置

在 `modules/shell/` 新建 Gradle 子项目，编译为独立 artifact `io.github.nebula.ktx:shell`，同时通过 `KtsScriptDefinition.defaultImports` 将其核心 API 注入 ktx 脚本上下文。

- 独立 artifact：可被任何 Kotlin/JVM 项目直接依赖，不限于 ktx 脚本。
- 默认导入：ktx 脚本开箱即用，无需 `@file:DependsOn`。

### 模块划分

| 模块 | 坐标 | 内容 | ktx 是否默认导入 |
|---|---|---|---|
| `shell-core` | `io.github.nebula.ktx:shell-core` | 进程执行、参数转义、管道、Path 扩展、重试、sleep、跨平台 shell parser | 是 |
| `shell-terminal` | `io.github.nebula.ktx:shell-terminal` | Mordant 终端 UI：prompt、progress、颜色日志 | 是 |

不做 `shell-http`：ktor-client 已经足够简单，用户需要时自己 `@file:DependsOn` 导入即可。

### 模块命名

就叫 `shell`。虽然 `stdlib` 更宽泛，但当前 scope 明确是 shell 相关能力，语义精确比未来扩展性更重要。如果后续需要更通用的脚本标准库，再新建 `stdlib` 模块也不迟。

## API 设计：Kotlin 地道语法映射

### 核心命令执行

```kotlin
val branch = sh"git rev-parse --abbrev-ref HEAD".text()
val env = terminal.select("Where to?", listOf("staging", "production"))
```

| dax 能力 | Kotlin 风格 API | 语法特性 |
|---|---|---|
| `` $`cmd ${arg}` `` | `sh"cmd $arg"` | `String.invoke()` + 自定义 `ShString` |
| `.text()` / `.json()` / `.lines()` | `sh"cmd".text()` / `.json<MyType>()` / `.lines()` | 扩展函数 + `reified` 泛型 |
| `.bytes()` | `.bytes()` / `.byteFlow()` | `Flow<Byte>` 流式 |
| `.pipe()` | `sh"cmd1" pipe sh"cmd2"` | `infix` 操作符 |
| `.env()` / `.cwd()` | `sh"cmd".env("K", "V").cwd("/tmp")` | builder 链式 |
| `.quiet()` | `sh"cmd".quiet()` | 扩展函数 |
| `.noThrow()` | `sh"cmd".ignoreExitCode()` | 扩展函数 |
| `.timeout()` | `sh"cmd".withTimeout(5.seconds)` | `kotlin.time.Duration` |
| `.printCommand()` | `sh"cmd".echo()` | 扩展函数 |
| `$.all()` | `shAll { +sh"cmd1"; +sh"cmd2" }` | DSL + `unaryPlus` |
| `$.confirm()` / `$.select()` | `terminal.confirm("?")` / `terminal.select("?", options)` | Mordant 封装 |
| `$.progress()` | `progress("msg") { ... }` / `progress("msg", length) { ... }` | `suspend` lambda |
| `$.sleep()` | `delay(1.5.seconds)` 或 `sleep(1.5.seconds)` | `kotlin.time.Duration` |
| `$.withRetries()` | `retry(times = 5, delay = 5.seconds) { ... }` | 具名参数 + `suspend` |
| `$.path()` | `path("foo")` 或 `"foo".toPath()` | `kotlin.io.path` 扩展 |

### 协程作用域

脚本顶层默认在 `CoroutineScope` 内执行，`sh"..."` 作为 `suspend` 函数可直接调用，无需用户写 `runBlocking`。

实现方式：在 `KtsScript` 基类中注入一个 `CoroutineScope`，让脚本顶层默认在该 scope 内执行。

## 实现细节

### `sh"..."` 字符串插值与参数安全

Kotlin 没有真正的 tagged template literal，用 `operator fun String.invoke()` 实现：

```kotlin
operator fun String.invoke(vararg args: Any?): ShCommand = ShCommand(this, args.toList())
```

`ShCommand` 内部区分：
- `String` → 自动转义（空格、引号）
- `Path` → 转为绝对路径字符串
- `RawArg(string)` → 不转义，直接拼接
- `ShCommand` → 嵌套命令（用于 `.stdin(other)`）

### 进程执行引擎

基于 `ProcessBuilder` + `kotlinx.coroutines`，不引入 commons-exec 等外部库。

```kotlin
class ShCommand(
    val template: String,
    val args: List<Any?>,
) {
    suspend fun execute(): ShResult
    fun spawn(): ShChild  // 返回可取消/可管道的 child
}
```

`ShResult` 包含：
- `exitCode: Int`
- `stdout: String`
- `stderr: String`
- `stdoutBytes: ByteArray`
- `stdoutJson<T>(): T`（依赖 kotlinx.serialization）

### 管道

```kotlin
infix fun ShCommand.pipe(other: ShCommand): ShCommand =
    this.copy(stdoutRedirect = Redirect.Pipe(other))
```

或通过 `ProcessBuilder` 的 `redirectOutput(ProcessBuilder.Redirect.PIPE)` 链式启动。

### 跨平台 shell parser

`sh"..."` 不走系统 `/bin/sh` 或 `cmd.exe`，而是内置一个 cross-platform shell parser，支持：

- `&&` / `||` / `;`
- `|` 管道
- `>` / `<` / `>>` 重定向
- subshell `(...)`
- `export` / `cd` / `unset`
- 环境变量展开 `$VAR` / `${VAR}`
- glob `*` / `**` / `?`

在 JVM 上解析 AST，然后按节点逐个 spawn `ProcessBuilder` 并连接管道/重定向。Windows 上内置常用命令（`cp`, `mv`, `rm`, `mkdir`, `echo`, `cat`, `pwd`, `which`, `sleep`, `touch`, `true`, `false`, `test`, `printenv`）的纯 Kotlin 实现，保证脚本可移植性。

参考实现：dax 使用的 `deno_task_shell` parser（Rust），需移植或重写为 Kotlin。

### 终端 UI（Mordant）

Mordant 与 clikt 同作者，已在 ktx 依赖树中（clikt 传递依赖 mordant-core）。ktx 当前 Kotlin 2.2.0，兼容 mordant 3.x。

封装 thin wrapper：
- `terminal`：单例或 `context(Terminal)` 接收者
- `terminal.confirm()` / `terminal.prompt()` / `terminal.select()` / `terminal.multiSelect()`
- `progress()` 函数返回 `ProgressBar` 对象，支持 `.increment()`、`.message()`、`.with { ... }`

### 并发

`shAll { }` DSL 内部收集命令，退出块时统一 `awaitAll()`。

或者更 Kotlin 风格：直接提供 `List<ShCommand>.awaitAll()` 扩展。

## Gradle 配置变更

1. `settings.gradle.kts` 增加 `include(":modules:shell")`
2. `gradle/libs.versions.toml` 增加 `mordant = "3.0.2"`
3. `modules/shell/build.gradle.kts` 依赖：
   - `api(libs.kotlinx.coroutines.core)`（JVM）
   - `api(libs.mordant)`
   - `api(libs.kotlinx.serialization.json)`
4. `modules/core/build.gradle.kts` 增加 `implementation(project(":modules:shell"))`

## 与现有 ktx 的集成点

1. **`KtsScriptDefinition.defaultImports`**：
   ```kotlin
   defaultImports(
       "io.github.nebula.ktx.shell.*",
       "io.github.nebula.ktx.shell.terminal.*",
   )
   ```
2. **Toolchain 感知**：`sh"..."` 默认继承脚本运行时的 JDK 环境（`ProcessBuilder` 天然继承）。如需切换 JDK，可用 `.env("JAVA_HOME", toolchain.path)`。
3. **日志**：复用 ktx 现有的 slf4j/logback，Mordant 的 terminal 输出走 stderr（与 dax 一致）。

## 分阶段实施计划

| 阶段 | 内容 | 预估 |
|---|---|---|
| **Phase 1** | `shell-core`：基础 `sh"..."`、`.text()`、`.json()`、`.lines()`、`.bytes()`、管道、`.quiet()`、`.ignoreExitCode()`、`.withTimeout()`、Path 扩展 | 3 天 |
| **Phase 2** | 跨平台 shell parser：AST 定义、lexer、parser、内置命令、重定向/管道/逻辑组合执行 | 2 周 |
| **Phase 3** | `shell-terminal`：Mordant 集成，`terminal.confirm/prompt/select/multiSelect`，`progress()` 定值/不定值 | 2 天 |
| **Phase 4** | 高级特性：`.spawn()` + `ShChild`、流式 `.byteFlow()` / `.lineFlow()`、重试 DSL、`shAll { }` | 2 天 |
| **Phase 5** | docs、reports、CI、样本脚本 | 2 天 |

**关键路径**：Phase 2 的 cross-platform shell parser 是整个计划中最重的部分。如果实现代价超预期，可先降级为「基于系统 shell（`/bin/sh -c` / `cmd.exe /c`）+ Windows 常用命令 polyfill」，把 parser 推迟到 Phase 4 之后。

## 关键权衡点（已确认）

| # | 问题 | 选择 |
|---|---|---|
| 1 | HTTP 客户端是否内置？ | **不做**。ktor-client 已足够简单，用户需要时自己导入。 |
| 2 | 跨平台 shell 策略？ | **完整实现**。移植/重写 cross-platform shell parser，内置常用命令的 Kotlin 实现，保证 Windows 可移植性。 |
| 3 | 模块命名？ | **`shell`**。语义精确，未来需要更通用 stdlib 时再扩展。 |
| 4 | 协程默认作用域？ | **脚本级**。在 `KtsScript` 基类中注入 `CoroutineScope`，脚本顶层默认在 scope 内执行，无需 `runBlocking`。 |

## 相关链接

- dax 文档：https://dax.land/
- Mordant：https://ajalt.github.io/mordant/
- kotlin-shell (OlegKrikun)：https://github.com/OlegKrikun/kotlin-shell —— 持久 shell 进程 + Flow 输出
- Khell：https://github.com/ElisaMin/Khell —— 极简 200 行 Process 封装
