# Phase 3+：未做的事 (TODO)

> 时间：2026-05-31（Phase 2 收尾时整理）
>
> 这一份记录所有 Phase 2 没做、留给后续的功能与优化。每条都是「想清楚了但还没动手」的状态。

## Phase 3.1：`ktx compile --self-contained` ✅ 已完成（2026-05-31）

`ktx compile <script> --self-contained -o <dir>` 已上线：用 `jdeps` 分析 fat jar，`jlink --compress=2 --strip-debug` 出最小 JRE，配合 launcher 脚本组成 `bin/lib/runtime` 三件套目录。end user 拿到目录，零 Java 依赖直接 `bin/<name>` 执行。

实现：`modules/core/.../compile/SelfContainedFlow.kt` + `CompileCommand` 加 `--self-contained` flag。详见 `docs/guide/compile.md`「Self-contained: zero Java required」一节。

**本期未做、后续可做**：

- jpackage 包成 `.dmg`/`.deb`/`.exe` installer（参见下方）。
- 跨平台 release matrix（jlink 不能交叉编译，要分别在 macOS / Linux / Windows runner 上跑）。
- jlink 产物缓存（单次 ~3-5s，目前不值得加缓存维护代价）。

## Phase 3.1.5：jpackage installer

把 Phase 3.1 的 `bin/lib/runtime` 目录树进一步包成各平台原生 installer：

- macOS：`.dmg` 或 `.pkg`，应用进 `/Applications/<name>.app`。需要 Apple Developer ID + codesign + notarize（CI 上 `apple-actions/import-codesign-certs`）。
- Linux：`.deb` / `.rpm`。`jpackage --type deb` 直接出包。
- Windows：`.msi` 或 `.exe`，需要 WiX Toolset（CI runner 自带）。

**实现路径**：在 `SelfContainedFlow` 之后增加一个 `InstallerFlow`，调用 `jpackage --type <fmt> --app-image <selfContainedDir> --dest dist/`。

**估计工作量**：1 周（含 macOS 签名调试）。


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

## `ktx daemon logs` 子命令 ✅ 已完成（2026-05-31）

支持 `--tail N` / `--all` / `--jdk` / `--path`。详见 `modules/cli/.../command/DaemonCommand.kt:DaemonLogsCommand` 与 `docs/guide/daemon.md` 的「Inspecting daemon logs」章节。`--path` 让 `tail -f "$(ktx daemon logs --path)"` 模拟 follow 行为，避免在 ktx 里重新实现 watch service。

**已知遗留**：`kotlin-main-kts` jar 自带的 `slf4j-simple` binding 在 daemon classpath 上有时会赢过 logback，导致 `~/.cache/ktx/d/<key>/log` 实际为空（slf4j-simple 写到 stderr 然后被 ProcessBuilder.DISCARD 吃掉）。修法是把 main-kts 中 `org/slf4j/impl/` 在 install 阶段排除掉，独立 issue 处理。

## `ktx cache info / clean / gc` 子命令 ✅ 已完成（2026-05-31）

- `ktx cache info`：路径、条目数、总大小、最旧/最新条目时间。
- `ktx cache clean`：清空 `~/.cache/ktx/compiled/`。
- `ktx cache gc [--max-size 2GB]`：LRU 淘汰直到总大小 ≤ cap。

实现：`modules/cli/.../command/CacheCommand.kt`，复用 `ScriptRunner.defaultCacheDir()`。`gc` 只看 mtime + 总大小两个维度，不涉及年龄/条目数上限。daemon dir（`~/.cache/ktx/d/`）不归 cache 命令管。

## stdin pump shutdown 优化 ✅ 已验证不复现（2026-05-31）

原 reports 描述：`--forward-stdin` 开启后 ktx CLI 进程退出延迟 ~300ms，pump 线程卡在 native `System.in.read` 上。

实测：在 macOS Sonoma + JDK 21 上，`--forward-stdin` 即使 stdin 来源不 EOF（被 sleep 5 hold 住），ktx CLI 进程自身寿命仍稳定在 ~115-140ms。问题不复现。猜测是 daemon thread + native read 的行为在某版 JDK 之后已修。如未来其它平台/版本再现，再考虑 `Channels.newChannel(System.in) + close()` 中断方案。




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
