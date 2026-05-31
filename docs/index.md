---
layout: home

hero:
  name: "ktx"
  text: "A modern Kotlin script runner"
  tagline: "What uv is for Python and bun is for JavaScript, ktx aims to be for .kts"
  image:
    src: /logo-mark.svg
    alt: ktx logo
  actions:
    - theme: brand
      text: Get Started
      link: /guide/getting-started
    - theme: alt
      text: Performance
      link: /performance
    - theme: alt
      text: View on GitHub
      link: https://github.com/gaojunran/ktx

features:
  - icon: ⚡
    title: Fast warm starts
    details: Daemon mode keeps the embedded Kotlin compiler resident. Repeated runs land at ~120 ms; new scripts in a hot daemon land at ~250 ms (vs. ~750 ms cold).
  - icon: 🔒
    title: Reproducible by default
    details: ktx lock writes a *.kts.lock pinning every transitive jar. ktx run --frozen runs offline, makes CI deterministic.
  - icon: 🎯
    title: Per-script JDK
    details: Declare @file:Toolchain(jdk = "17") and ktx auto-downloads from Adoptium and re-execs onto the right JVM.
  - icon: 📦
    title: Compile to standalone jar
    details: ktx compile foo.kts produces an 11–13 MB fat jar that runs on plain JRE 17+. End users do not need ktx, do not need a Kotlin compiler.
  - icon: 🛠️
    title: Reuses kotlin-main-kts
    details: Built on top of JetBrains' supported scripting host. @file:DependsOn, @file:Repository, @file:Import all work out of the box. Zero migration cost from .main.kts.
  - icon: 🧩
    title: Modular architecture
    details: CLI, daemon, protocol, toolchain manager, core compiler logic — each in its own Gradle module, each replaceable.
---

## Install

```bash
mise use -g github:gaojunran/ktx@latest
```

That's it — [mise](https://mise.jdx.dev/) downloads the right archive from GitHub Releases and puts `ktx` on your `PATH`. See [Getting Started](/guide/getting-started) for archive downloads, building from source, and per-project pinning.

## At a glance

```bash
# Run a script
ktx hello.main.kts

# Eval an expression
ktx -e 'println(2 + 3)'

# Run via daemon (warm starts in ~120 ms)
ktx run --daemon foo.kts

# Lock dependencies for reproducible CI
ktx lock foo.kts
ktx run --frozen foo.kts

# Compile to a self-contained fat jar
ktx compile foo.kts -o foo.jar
java -jar foo.jar     # runs on plain JRE 17+
```

## Why another script runner?

The official `kotlin foo.main.kts` workflow has three long-standing pain points:

1. **Cold starts of 1.5–5 s** every time you change a single line of script.
2. **No lockfile**, so a script you wrote last month may pull a different transitive tomorrow.
3. **No JDK management** — you must install the right JDK ahead of time.

The community alternative [`kscript`](https://github.com/kscripting/kscript) was last released in 2023 and does not support Kotlin 2.0+.

ktx fills this gap. It builds on JetBrains' [State of Kotlin Scripting 2024](https://blog.jetbrains.com/kotlin/2024/11/state-of-kotlin-scripting-2024/) maintained surface (`kotlin-main-kts` and the JVM scripting host), wraps it with a CLI inspired by uv and bun, and adds a daemon to amortize compiler cold starts.
