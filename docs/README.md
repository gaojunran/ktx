# 实施日志索引

按时间倒序，最新的在最上面。

| 阶段 | 主题 | 文档 |
|---|---|---|
| Phase 2.1 | daemon 最小可运行版（dev loop 加速 60%） | [phase-2.1.md](./phase-2.1.md) |
| Phase 1.4 | AppCDS 归档削掉 ~80ms 启动开销 | [phase-1.4.md](./phase-1.4.md) |
| Phase 1.3 | JDK 工具链管理 + `@file:Toolchain(jdk=...)` 真实路由 | [phase-1.3.md](./phase-1.3.md) |
| Phase 1.2 | lockfile + `--frozen` + `kts add` | [phase-1.2.md](./phase-1.2.md) |
| Phase 1.1 | CLI 主干 (`run` / `-e` / stdin) + `@file:Toolchain` + 编译缓存目录化 | [phase-1.1.md](./phase-1.1.md) |
| Phase 0   | 复用 `kotlin-main-kts` 跑通 `.main.kts` | [phase-0.md](./phase-0.md) |

完整路线图见仓库根 [README.md](../README.md)。
