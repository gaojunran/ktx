# 实施日志（Implementation Reports）

> 这一目录里的内容是 **中文** 的，记录开发过程中每一波改动的设计决策、性能数据、踩过的坑。
> 用户文档（英文）见 `docs/`（VitePress 站点）。
>
> The reports here are written in Chinese on purpose: they are the
> developer-facing journals describing why each phase was done a certain
> way. The user-facing English documentation lives in `docs/` (VitePress).

按时间倒序，最新的在最上面。

| 阶段 | 主题 | 文档 |
|---|---|---|
| Phase 3+ | TODO 列表（self-contained / native image / 多 Kotlin 版本等） | [phase-3-todo.md](./phase-3-todo.md) |
| Phase 2.4 | `ktx compile` 把脚本编译成独立 fat jar | [phase-2.4.md](./phase-2.4.md) |
| Phase 2.3 | 流式 stdin + `--lock` via daemon | [phase-2.3.md](./phase-2.3.md) |
| Phase 2.2 | daemon 路由 + 并发 + 生命周期 + frozen via daemon | [phase-2.2.md](./phase-2.2.md) |
| Phase 2.1 | daemon 最小可运行版（dev loop 加速 60%） | [phase-2.1.md](./phase-2.1.md) |
| Phase 1.4 | AppCDS 归档削掉 ~80ms 启动开销 | [phase-1.4.md](./phase-1.4.md) |
| Phase 1.3 | JDK 工具链管理 + `@file:Toolchain(jdk=...)` 真实路由 | [phase-1.3.md](./phase-1.3.md) |
| Phase 1.2 | lockfile + `--frozen` + `kts add` | [phase-1.2.md](./phase-1.2.md) |
| Phase 1.1 | CLI 主干 (`run` / `-e` / stdin) + `@file:Toolchain` + 编译缓存目录化 | [phase-1.1.md](./phase-1.1.md) |
| Phase 0   | 复用 `kotlin-main-kts` 跑通 `.main.kts` | [phase-0.md](./phase-0.md) |
