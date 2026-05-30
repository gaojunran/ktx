# ktx

A modern Kotlin script runner — what `uv` is for Python, ktx aims to be for `.kts`.

> 当前状态：**Phase 1 进行中**。CLI 主干 (`run` / `-e` / stdin) 与 `@file:Toolchain` 预扫描已上线，编译产物缓存指向 `~/.cache/ktx/compiled/`，命中缓存的 `.kts` 二次启动 ~220ms。
>
> 完整规划见 [`docs/plan.md`](docs/plan.md)（待补充）。

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
$KTX samples/ktor-client.main.kts           # 含 @file:DependsOn
$KTX -e 'println(2 + 3)'                    # 内联表达式
echo 'println("via stdin")' | $KTX run -    # 从 stdin 读
```

## 已实现 / 计划

| 阶段 | 内容 | 状态 |
|---|---|---|
| Phase 0 | 复用 `kotlin-main-kts` 跑通 `.main.kts` | 完成 |
| Phase 1.1 | CLI (`run` / `-e` / stdin) + `@file:Toolchain` 预扫描 + 编译缓存目录化 | 完成 |
| Phase 1.2 | `kts add` / `kts lock` / `--frozen` lockfile 模式 | 计划中 |
| Phase 1.3 | `kts toolchain install` (Adoptium API) | 计划中 |
| Phase 1.4 | CLI AppCDS 归档，CLI 启动 ~80ms | 计划中 |
| Phase 2 | daemon 化、多 Kotlin 版本支持 | 计划中 |
| Phase 3 | `kts compile`、`kts watch`、native image | 计划中 |

## 项目布局

```
modules/
  core/         # ScriptRunner、KtsScript ScriptDefinition、@file:Toolchain
  cli/          # clikt 子命令、ToolchainHeaderScanner、Main 入口
  toolchain/    # （计划中）JDK 下载与版本路由
  daemon/       # （计划中）UDS + protobuf RPC
  protocol/     # （计划中）.proto 定义
samples/        # 样例脚本
```
