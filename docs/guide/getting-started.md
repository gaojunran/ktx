# Getting Started

ktx is a CLI for running Kotlin `.kts` scripts. This page gets you from zero to a working `ktx` binary on your `PATH`.

## Prerequisites

- **JDK 21 or newer** to build ktx itself. ktx currently ships from source.
- **Git** to clone the repository.

ktx targets `JVM_17` bytecode, so once installed it can run on JDK 17+. The 21 requirement is purely for building.

## Build from source

```bash
git clone https://github.com/gaojunran/ktx.git
cd ktx
./gradlew :modules:cli:installDist
```

The output appears under `modules/cli/build/install/ktx/`:

```
modules/cli/build/install/ktx/
├── bin/
│   ├── ktx           # the launcher script (Unix)
│   └── ktx.bat       # Windows launcher
└── lib/              # all jars, including ktx.jsa (AppCDS archive)
```

Add `bin/` to your `PATH`:

```bash
export PATH="$PWD/modules/cli/build/install/ktx/bin:$PATH"
```

Or copy the whole `install/ktx/` directory to a stable location:

```bash
cp -r modules/cli/build/install/ktx ~/.local/share/ktx
ln -s ~/.local/share/ktx/bin/ktx ~/.local/bin/ktx
```

## Verify

```bash
ktx --help
ktx -e 'println("hello from ktx")'
```

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
