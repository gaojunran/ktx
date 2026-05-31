# ktx

A modern Kotlin script runner. What `uv` is for Python and `bun` is for JavaScript, ktx aims to be for `.kts`.

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

## Documentation

- **User guide** (English): see `docs/` for the VitePress site source. Run `cd docs && pnpm install && pnpm dev` to preview locally.
- **Implementation journals** (Chinese): see [`reports/`](reports/README.md) for the per-phase design notes, performance data, and gotchas.

## Quick start

```bash
# 1. install build prerequisites (JDK 21 + Gradle 8.14, declared in .mise.toml)
mise install

# 2. build the CLI distribution
mise exec -- ./gradlew :modules:cli:installDist

# 3. add the binary to your PATH
export PATH="$PWD/modules/cli/build/install/ktx/bin:$PATH"

# 4. run a sample
ktx samples/hello.main.kts foo bar
```

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
