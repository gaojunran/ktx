# ktx

A modern Kotlin script runner — what `uv` is for Python, ktx aims to be for `.kts`.

> 当前状态：**Phase 2.4 完成**。`ktx compile foo.kts` 输出独立 fat jar（11-13 MB），`java -jar` 直接跑，end user 只需 JRE 17+，不依赖 ktx 也不依赖 Kotlin 编译器。
>
> 每一波改动的详细中文实施日志见 [`docs/`](docs/README.md)。

## 环境要求

项目使用 [mise](https://mise.jdx.dev/) 管理 JDK 和 Gradle 版本，配置见 `.mise.toml`。

```bash
mise install            # 安装 .mise.toml 中声明的 JDK 21 + Gradle 8.14
```

如果你已在当前 shell 激活 mise（`mise activate`），下面的 `mise exec --` 前缀可省略。

## 构建与试用

```bash
mise exec -- ./gradlew :modules:cli:installDist
KTX=$(pwd)/modules/cli/build/install/ktx/bin/ktx
```

跑样例：

```bash
$KTX samples/hello.main.kts foo bar         # 默认子命令 = run
$KTX run samples/hello.main.kts foo bar     # 显式
$KTX samples/jackson.main.kts               # 含 @file:DependsOn
$KTX -e 'println(2 + 3)'                    # 内联表达式
echo 'println("via stdin")' | $KTX run -    # 从 stdin 读

$KTX lock samples/jackson.main.kts          # 写 <script>.lock
$KTX run --frozen samples/jackson.main.kts  # 只走 lockfile，不联网
$KTX add com.example:foo:1.0 samples/foo.kts # 插 @file:DependsOn 并刷 lockfile

$KTX toolchain install 17                   # 下载 Adoptium JDK 17
$KTX toolchain list                         # 列出已装 JDK
$KTX run samples/toolchain.main.kts         # 脚本声明 @file:Toolchain(jdk="17") 自动切换

$KTX run --daemon samples/hello.main.kts    # 通过 daemon 跑（首次 fork，后续暖）
$KTX daemon status                          # 查 daemon 状态
$KTX daemon stop                            # 优雅关闭

$KTX compile samples/hello.main.kts         # 编译成独立 fat jar
java -jar samples/hello.jar foo bar         # 跑产物（不需要 ktx，仅 JRE）
```

## 已实现 / 计划

| 阶段 | 内容 | 状态 |
|---|---|---|
| Phase 0 | 复用 `kotlin-main-kts` 跑通 `.main.kts` | 完成 |
| Phase 1.1 | CLI (`run` / `-e` / stdin) + `@file:Toolchain` 预扫描 + 编译缓存目录化 | 完成 |
| Phase 1.2 | `kts lock` / `kts add` / `--frozen` lockfile 模式 | 完成 |
| Phase 1.3 | `kts toolchain install / list / path` + `@file:Toolchain(jdk=...)` 路由 | 完成 |
| Phase 1.4 | CLI AppCDS 归档（启动开销 -80ms） | 完成 |
| Phase 2.1 | 最小 daemon（dev loop -60%） | 完成 |
| Phase 2.2 | daemon 路由 + 并发 + 生命周期 + frozen via daemon | 完成 |
| Phase 2.4 | `ktx compile` 编译为独立 fat jar | 完成 |
| Phase 2.3 | 流式 stdin + `--lock` via daemon | 计划中 |
| Phase 3 | `kts compile`、`kts watch`、native image | 计划中 |

## 项目布局

```
modules/
  core/         # ScriptRunner、KtsScript ScriptDefinition、@file:Toolchain
  cli/          # clikt 子命令、ToolchainHeaderScanner、Main 入口、daemon client
  toolchain/    # JDK 下载（Adoptium）、解压、版本管理
  daemon/       # UDS 服务端、Run handler
  protocol/     # protobuf 协议定义 + 帧编解码
samples/        # 样例脚本
```
