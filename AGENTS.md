# AGENTS.md

## Project Overview

**ktx** is a modern Kotlin script runner. What `uv` is for Python and `bun` is for JavaScript, ktx aims to be for `.kts`.

Key features: daemon mode (warm compiler, ~250 ms dev loop), `@file:Toolchain(jdk=...)` auto-JDK-install, lockfiles for CI reproducibility, `ktx compile` producing self-contained fat jars or GraalVM native binaries, and AppCDS for fast CLI startup.

### Repository Layout

```
modules/
  core/         ScriptRunner, KtsScript, ScriptDefinition, @file:Toolchain
  cli/          clikt subcommands, ToolchainHeaderScanner, daemon client
  toolchain/    JDK download (Adoptium), tar/zip extraction, version manager
  daemon/       UDS server, request handlers, lifecycle supervision
  protocol/     protobuf schema + length-prefix framing
samples/        sample scripts (hello, jackson, ktor-client, toolchain)
docs/           user-facing English documentation (VitePress)
reports/        per-phase implementation journals (Chinese)
benchmarks/     hyperfine benchmark suite
```

### Tech Stack

- Language: Kotlin (JVM target 17, toolchain JDK 21)
- Build: Gradle 8.14 (via mise), Kotlin/JVM
- CLI framework: clikt
- Docs: VitePress (docs/)
- Tooling: mise for tool version management and task running

## Workflow

### Prerequisites

Install [mise](https://mise.jdx.dev/) and run `mise install` in the repo root. This brings in JDK 21, Gradle 8.14, Node 20, pnpm, and all other tools declared in `mise.toml`.

### Before Every Commit

Run `mise run ci`. This executes the same pipeline as CI:

1. `mise run test` — unit tests
2. `mise run build` — builds the ktx CLI distribution into `modules/cli/build/install/ktx/`
3. `mise run docs:build` — builds the VitePress site

Do not push if any step fails.

### Common Tasks

| Command | Description |
|---|---|
| `mise run build` | Build ktx CLI distribution |
| `mise run test` | Run unit tests |
| `mise run test:watch` | Run tests continuously on file changes |
| `mise run run` | Build + run a sample script |
| `mise run dist` | Produce release archives (zip + tar.gz) |
| `mise run bench` | Run hyperfine benchmarks |
| `mise run render` | Regenerate CLI docs from clikt tree |
| `mise run docs:dev` | Start VitePress dev server at localhost:5173 |
| `mise run docs:build` | Build VitePress site |
| `mise run clean` | Clean Gradle outputs and ktx caches |
| `mise run daemon:stop` | Stop all running ktx daemons |
| `mise run daemon:status` | Show daemon status |

### CI

GitHub Actions runs on every push to `main` and on PRs. The pipeline: test, build, docs build, benchmarks (main only), CLI doc regeneration (main only), and deploy to GitHub Pages (main only). See `.github/workflows/ci.yml`.

## Documentation Requirements

### reports/ (Chinese Implementation Journals)

**Every commit that changes behavior must update the corresponding report in `reports/`.**

Reports are written in Chinese and track design decisions, performance data, and gotchas per phase. They are the developer-facing journals. When adding a new feature or fixing a bug, update the relevant phase report or create a new one.

### docs/ (English User Documentation)

**Update the VitePress docs when a change is user-facing.**

User-facing changes include: new CLI flags or subcommands, changed default behavior, new features visible to end users, API changes that script authors need to know about.

Changes that do NOT require docs updates: internal refactors, private API changes, test improvements, build system tweaks, performance optimizations with no observable behavior change.

The docs are built from `docs/` and deployed to [gaojunran.github.io/ktx](https://gaojunran.github.io/ktx/). Use `mise run docs:dev` to preview locally.
