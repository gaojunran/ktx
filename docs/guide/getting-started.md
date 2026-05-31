# Getting Started

ktx is a CLI for running Kotlin `.kts` scripts. This page gets you to a working `ktx` binary on your `PATH`.

## Install

### Via mise (recommended)

[mise](https://mise.jdx.dev/) installs ktx as a managed tool. It downloads the right release archive for your OS/arch from GitHub, places binaries on your `PATH` automatically, and makes pinning a specific version trivial.

```bash
# install latest globally
mise use -g github:gaojunran/ktx@latest

# or pin a specific version
mise use -g github:gaojunran/ktx@0.0.1

# verify
ktx --help
```

To pin per project, add this to a `mise.toml` or `.mise.toml` at the repo root:

```toml
[tools]
"github:gaojunran/ktx" = "latest"
```

mise's GitHub backend uses the [GitHub Releases](https://github.com/gaojunran/ktx/releases) of this repo. There is no separate registry to maintain.

::: tip Why mise?
ktx is itself a Kotlin/JVM project — it needs a JDK to run. mise can manage both at once:
```toml
[tools]
java = "temurin-21"
"github:gaojunran/ktx" = "latest"
```
Now `cd`-ing into a directory with this `mise.toml` gives you the right JDK + the right ktx, no global pollution.
:::

### Via release archive

Download `ktx-<version>.tar.gz` (Unix) or `ktx-<version>.zip` (Windows) from the [releases page](https://github.com/gaojunran/ktx/releases), extract anywhere, and add `bin/` to your `PATH`:

```bash
curl -L https://github.com/gaojunran/ktx/releases/download/v0.0.1/ktx-0.0.1.tar.gz -o ktx.tar.gz
tar -xzf ktx.tar.gz
export PATH="$PWD/ktx-0.0.1/bin:$PATH"
```

Each release also publishes `SHASUMS256.txt`. Verify with:

```bash
shasum -a 256 -c <(grep ktx-0.0.1.tar.gz SHASUMS256.txt)
```

### Build from source

You'll need this if you want to hack on ktx itself or want a build of an unreleased commit.

```bash
git clone https://github.com/gaojunran/ktx.git
cd ktx
mise install                                # provisions JDK 21 + Gradle 8.14
mise run build                              # ./gradlew :modules:cli:installDist
export PATH="$PWD/modules/cli/build/install/ktx/bin:$PATH"
```

The output appears under `modules/cli/build/install/ktx/`:

```
modules/cli/build/install/ktx/
├── bin/
│   ├── ktx           # Unix launcher
│   └── ktx.bat       # Windows launcher
└── lib/              # all jars, including ktx.jsa (AppCDS archive)
```

## Verify

```bash
ktx --help
ktx -e 'println("hello from ktx")'
```

## Runtime requirements

ktx needs a **JDK 17 or newer** at runtime. mise users get this implicitly; archive-install users must install a JDK separately or let ktx do it via [`ktx toolchain install`](./toolchain).

ktx does not require a separate Kotlin compiler install — its bundled `kotlin-compiler-embeddable.jar` is self-contained.

## Your first script

```bash
cat > hello.kts <<'EOF'
println("hello, args = ${args.toList()}")
EOF
ktx hello.kts foo bar
```

::: tip
ktx accepts both `.kts` and `.main.kts` extensions. The latter signals
"main-kts compatible" to your IDE; ktx itself does not differentiate.
:::

## A script with dependencies

ktx supports the same `@file:DependsOn` annotation as `kotlin-main-kts`:

```kotlin
#!/usr/bin/env ktx

@file:DependsOn("com.fasterxml.jackson.core:jackson-databind:2.18.0")

import com.fasterxml.jackson.databind.ObjectMapper

val data = mapOf("name" to "ktx", "version" to "0.0.1")
val json = ObjectMapper().writeValueAsString(data)
println(json)
```

Run it:

```bash
ktx jackson.main.kts
```

The first run downloads `jackson-databind` and its transitives, compiles the script, and executes. Subsequent runs hit the compiled-script cache and start in ~250 ms.

## Speed it up further with the daemon

```bash
ktx run --daemon hello.kts
```

The first invocation forks a background daemon; subsequent runs reuse it and start in **~120 ms**. See the [Daemon Mode](./daemon) page for details.

## What next

- **[CLI reference](./cli)** — every subcommand and flag.
- **[Lockfiles](./lockfile)** — make scripts reproducible across machines.
- **[Compiling scripts](./compile)** — produce a 13 MB fat jar that runs on plain JRE.
- **[Performance](/performance)** — benchmarks and where the time goes.
