# Phase 3+：未做的事 (TODO)

> 时间：2026-05-31（Phase 2 收尾时整理）
>
> 这一份记录所有 Phase 2 没做、留给后续的功能与优化。每条都是「想清楚了但还没动手」的状态。

## Phase 3.1：`ktx compile --self-contained`

把 Phase 2.4 的 fat jar 进一步封装成「带最小 JRE 的目录树」，end user 连 JRE 都不用装。

**实现路径**：

1. Phase 2.4 已产出 fat jar（11-13 MB）。
2. `jdeps --print-module-deps foo.jar` 分析需要的 JDK 模块。
3. `jlink --add-modules <list> --output runtime/` 出最小 JRE（30-40 MB）。
4. `jpackage --type app-image --runtime-image runtime --main-jar foo.jar --dest dist/`。
5. 产物 `dist/foo/` 目录，含 `bin/foo` 启动脚本和 `lib/runtime/`。
6. 用户 `./foo/bin/foo` 直接跑，零依赖。

**关键 trade-off**：

- 总体积 ~50-60 MB（fat jar 13 MB + 最小 JRE ~40 MB）。
- 跨平台需要在每个目标平台分别 build —— `jlink` 不能交叉。CI 配 macOS/Linux/Windows 三 runner。
- 如果 Phase 2.4 fat jar 已含 reflect 等动态特性，jlink 必须包含 `java.base` + `java.logging` + `java.naming` + `jdk.unsupported`（kotlin-reflect 用到）。

**估计工作量**：1 周。主要在 build 脚本 + 跨平台 CI。

## Phase 3.2：`ktx compile --native`

GraalVM native image。产物 ~10-15 MB 单文件，启动 ~10ms。

**挑战**：

- `kotlin-reflect` 需要大量配置才能在 native image 跑通（reachability metadata）。GraalVM 社区有 Kotlin reflection 的 partial 支持，但 main-kts 编译产物用反射的方式比较深，可能需要手写 `reflect-config.json` / `resource-config.json`。
- 脚本里 `@file:DependsOn` 的依赖如果用 reflection（如 jackson-databind 反射映射 POJO），同样需要配置。
- `--initialize-at-build-time` vs `--initialize-at-run-time` 的取舍——错了会编译失败或运行时 NPE。

**估计工作量**：2-3 周。坑深，需要逐个依赖调试。

**先决条件**：建议在 Phase 3.1 上线 + 收用户反馈后再做。

## 多 Kotlin 编译器版本支持

Phase 2.2 的 toolchain 路由按 JDK 主版本分 daemon。Phase 2.3 没做的：脚本声明 `@file:Toolchain(kotlin = "2.3.0")` 让 ktx 用对应版本编译器。

**实现路径**：

1. ToolchainStore 增加 Kotlin 编译器管理：从 Maven Central 下载 `kotlin-compiler-embeddable-<version>.jar`。
2. ToolchainKey 加入 kotlinVersion 维度：`sha256(jdkMajor + kotlinVersion + protocolVersion)`。
3. daemon JVM 用独立 ClassLoader 加载特定版本的 compiler-embeddable，避免与主进程的版本冲突。
4. `KtsScriptDefinition` 在那个 ClassLoader 里实例化。

**关键难点**：

- ScriptDefinition class 需要在 isolated ClassLoader 里加载，`@KotlinScript` 注解的反射查找跨 ClassLoader 可能不通。
- main-kts 内部依赖 IntelliJ Platform 部分 API，不同 Kotlin 版本对应不同的 IDEA 内核 SHA，可能 import 路径变。
- 测试矩阵爆炸：Kotlin 2.0 / 2.1 / 2.2 / 2.3 各跑过都要确认。

**估计工作量**：2 周。

**优先级**：低。绝大多数用户用最新稳定版，跨 Kotlin 版本的脚本是边缘需求。等用户实际反馈再做。

## `ktx daemon logs` 子命令

让用户不用记 daemon log 路径就能看。

**实现**：

```kotlin
class DaemonLogsCommand : CliktCommand(name = "logs") {
    private val tail by option("--tail", "-n").int().default(50)
    private val follow by option("--follow", "-f").flag()
    private val jdkOption by option("--jdk").int()

    override fun run() {
        val daemonDir = jdkOption?.let { DaemonPaths.daemonDirFor(it) } ?: DaemonPaths.defaultDaemonDir()
        val logFile = DaemonPaths.logFile(daemonDir)
        // 用 Tailer 库或自己实现 tail-f
    }
}
```

**估计工作量**：半天。

## `ktx cache info / clean / gc` 子命令

`~/.cache/ktx/compiled/` 里编译产物 jar 可能堆积。需要：

- `ktx cache info`：显示总占用、文件数、最老/最新时间
- `ktx cache clean`：清空
- `ktx cache gc`：按 LRU + 总大小上限（默认 2GB）淘汰

**估计工作量**：半天。

## stdin pump shutdown 优化

Phase 2.3 已知问题：`--forward-stdin` 开启后 ktx 进程退出延迟 ~300ms，因为 stdin pump 阻塞在 native read，JVM shutdown 等它解开。

**解决方向**：用 NIO non-blocking read + selector，主线程退出前主动 cancel pump 的 read。或者干脆用 `Channels.newChannel(System.in)` 走 channel 模式，channel close 能立刻中断 read。

**估计工作量**：1-2 天。

**优先级**：低。绝大多数脚本不用 `--forward-stdin`，开了的也容忍 300ms。

## 协议版本演化

Phase 2.3 的字段（StdinChunk、ResolverMode.LOCK）都是 proto3 兼容性扩展，PROTOCOL_VERSION 仍是 1。等到有不兼容改动时（比如改帧格式、删字段）再升 v2。

**机制已就绪**：CLI 和 daemon 启动时比较 PROTOCOL_VERSION，不一致让 CLI 杀旧 daemon 重启新版本。

## TOML 元数据头

Phase 1 立项时讨论过：除了 `@file:Toolchain` 注解，还可以支持 PEP 723 风格的 TOML 头：

```kotlin
//> /// kts
//> kotlin = "2.2.0"
//> jdk = "21"
//> dependencies = ["io.ktor:ktor-client-core-jvm:3.0.0"]
//> ///
```

**优势**：可解析为纯结构化数据（IDE 友好），与 lockfile 格式同源。

**劣势**：与 main-kts 的 `@file:DependsOn` 重复表达。两套格式让用户困惑。

**决定**：**暂不做**。当前 `@file:Toolchain` + `@file:DependsOn` 已覆盖需求。如果有 IDE 厂商主动支持 TOML 块、能解析出依赖给补全用，再考虑。

## 跨平台测试 CI

目前只在 macOS aarch64 上手测过。需要：

- GitHub Actions matrix：macOS x64 / macOS aarch64 / Ubuntu / Windows
- 跑 `./gradlew test` + 端到端样例
- daemon 在 Windows 上的 UDS 路径（Win10 1803+ 支持）实际验证
- `ktx compile` 产物在三平台 java -jar 跑通

**估计工作量**：1 周（含坑）。

## 性能优化候选

Phase 2 之后还能榨的几处启动时间：

| 现状 | 优化方向 | 预期收益 |
|---|---|---|
| daemon 暖时 ~120ms | CLI 前端 GraalVM native image | -100ms |
| daemon 冷启 ~700ms | Project Leyden AOT cache (JDK 25) | -200ms |
| `ktx compile` 产物 13MB | ProGuard / R8 + reflect keep rules | -3MB |
| daemon JVM heap 100MB+ | 精细化 ScriptRunner 内 ClassLoader 隔离 | 节省常驻内存 |

**优先级**：低。当前性能已达 SLO，先把功能补完整。

## 总结

Phase 2 的范围正好停在「日常可用」的位置。剩下这些 TODO 全都是「锦上添花」级别——做了更好但不做也不影响核心使用场景。按用户反馈实际优先级排，不预先承诺时间。

最有价值的下一步是 **Phase 3.1 self-contained**——它真正打开「分发给非开发者」的场景，是 ktx 立项愿景的最后一块拼图。
