<p align="center">
  <a href="https://gaojunran.github.io/ktx/">
    <img src="assets/logo.png" alt="ktx" width="200">
  </a>
</p>

<p align="center">
  A modern Kotlin script runner. What <code>uv</code> is for Python and <code>bun</code> is for JavaScript, ktx aims to be for <code>.kts</code>.
</p>

<p align="center">
  <a href="https://gaojunran.github.io/ktx/"><img alt="Documentation" src="https://img.shields.io/badge/docs-gaojunran.github.io%2Fktx-blue?logo=vitepress&logoColor=white"></a>
  <a href="https://github.com/gaojunran/ktx/releases"><img alt="Latest release" src="https://img.shields.io/github/v/release/gaojunran/ktx?include_prereleases&sort=semver&logo=github"></a>
  <a href="https://github.com/gaojunran/ktx/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/gaojunran/ktx/actions/workflows/ci.yml/badge.svg?branch=main"></a>
</p>

> 📖 **Full documentation lives at [gaojunran.github.io/ktx](https://gaojunran.github.io/ktx/)** — guides, CLI reference, performance benchmarks, and design notes.

```bash
ktx hello.main.kts                     # run a script
ktx -e 'println(2 + 3)'                # eval an expression
ktx run --daemon foo.kts               # warm-daemon mode (~120 ms)
ktx run --frozen foo.kts               # offline / CI mode (lockfile required)
ktx lock foo.kts                       # write foo.kts.lock
ktx add com.example:foo:1.0 foo.kts    # insert @file:DependsOn and refresh lockfile
ktx compile foo.kts -o foo.jar         # produce a self-contained fat jar
ktx toolchain install 17               # download a JDK from Adoptium
```

## Highlights

- **Daemon mode** keeps the embedded Kotlin compiler warm; dev-loop runs drop from ~750 ms to ~250 ms.
- **`@file:Toolchain(jdk = "17")`** auto-installs and re-execs onto the requested JDK.
- **Lockfiles** (`*.kts.lock`) make scripts CI-reproducible: `--frozen` runs without network access.
- **`ktx compile`** emits a 11–13 MB fat jar that runs on plain JRE 17+, no ktx required.
- **AppCDS** bakes class metadata at install time, shaving ~80 ms off every CLI invocation.

## Install

The fastest way to try ktx is via [mise](https://mise.jdx.dev/):

```bash
mise use -g github:gaojunran/ktx@latest
ktx --help
```

Or download a release archive directly: <https://github.com/gaojunran/ktx/releases>

For building from source, see [Getting Started](https://gaojunran.github.io/ktx/guide/getting-started).

## Documentation

- **User guide** (English): <https://gaojunran.github.io/ktx/> — built from `docs/` (VitePress).
- **Implementation journals** (Chinese): see [`reports/`](reports/README.md) for the per-phase design notes, performance data, and gotchas.

## Project layout

```
modules/
  core/         ScriptRunner, KtsScript ScriptDefinition, @file:Toolchain
  cli/          clikt subcommands, ToolchainHeaderScanner, daemon client
  toolchain/    JDK download (Adoptium), tar/zip extraction, version manager
  daemon/       UDS server, request handlers, lifecycle supervision
  protocol/     protobuf schema + length-prefix framing
samples/        sample scripts (hello, jackson, ktor-client, toolchain)
docs/           user-facing English documentation (VitePress)
reports/        per-phase implementation journals (Chinese)
```

## License

TBD.
