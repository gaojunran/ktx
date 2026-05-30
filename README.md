# ktx

A modern Kotlin script runner — `kts` for `.kts` what `uv` is for Python.

> 当前状态：**Phase 0（可行性原型）**。可执行 `.main.kts` 文件，包含 `@file:DependsOn`、`@file:Repository`、`@file:Import`、`@file:CompilerOptions` 等官方注解。
>
> 完整规划见 [`docs/plan.md`](docs/plan.md)（待补充）。

## 环境要求

项目使用 [mise](https://mise.jdx.dev/) 管理 JDK 和 Gradle 版本，配置见 `.mise.toml`。

```bash
mise install            # 安装 .mise.toml 中声明的 JDK 21 + Gradle 8.14
```

如果你已在当前 shell 激活 mise（`mise activate`），下面的 `mise exec --` 前缀可省略。

## Phase 0 验证

跑无依赖样例：

```bash
mise exec -- ./gradlew :modules:core:run \
  --args="samples/hello.main.kts foo bar"
```

跑带 Maven 依赖（首次约需联网下载 ktor）：

```bash
mise exec -- ./gradlew :modules:core:run \
  --args="samples/ktor-client.main.kts"
```

## 项目布局（当前 / 目标）

```
modules/
  core/         # 编译执行核心。Phase 0 入口：Phase0Spike.kt
  cli/          # Phase 1 实现：clikt 子命令、shebang 兼容、AppCDS
  toolchain/    # Phase 1 实现：JDK 下载与版本路由
  daemon/       # Phase 2 实现：UDS + protobuf RPC
  protocol/     # Phase 2 实现：.proto 与生成代码
samples/        # 样例脚本
```

## 路线图

- **Phase 0**（已完成）：复用 `kotlin-main-kts` 跑通 `.main.kts`。
- **Phase 1**：CLI（`run` / `-e` / stdin / `lock` / `add` / `cache` / `toolchain`）、`@file:Toolchain` 注解、编译产物缓存目录化、lockfile 与 `--frozen` 模式、AppCDS。
- **Phase 2**：daemon 化、多 Kotlin 版本支持。
- **Phase 3**：`kts compile`、`kts watch`、native image。
