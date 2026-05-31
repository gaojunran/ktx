# FAQ

## What's the easiest way to install ktx?

```bash
mise use -g github:gaojunran/ktx@latest
```

[mise](https://mise.jdx.dev/) downloads the right release archive from GitHub, extracts it, and puts `ktx` on your `PATH`. You can pin a specific version (`@0.0.1`) or update later (`mise upgrade`). See [Getting Started](./guide/getting-started) for other install options including build-from-source.

## How is ktx different from `kotlin foo.main.kts`?

The official `kotlin` CLI is the reference scripting host shipped with the Kotlin distribution. It works, but:

- Every invocation is a fresh JVM with a fresh embedded compiler. Cold starts are 1.5–5 seconds.
- No lockfile. Re-running a script tomorrow may pull a different transitive.
- No JDK management. You install the right JDK ahead of time, manually.

ktx adds a CLI inspired by uv and bun on top of the same `kotlin-main-kts` host:

- A daemon that keeps the compiler warm. Dev-loop runs land in ~250 ms instead of ~750 ms.
- `*.kts.lock` lockfiles. `--frozen` makes runs reproducible and offline-capable.
- `@file:Toolchain(jdk = "17")` triggers automatic JDK download.
- `ktx compile` produces a 13 MB fat jar that runs on plain JRE 17+.

## How is ktx different from `kscript`?

[kscript](https://github.com/kscripting/kscript) was a great project. Its last release was July 2023, and it does not support Kotlin 2.0+. ktx targets Kotlin 2.2+ on the surface that JetBrains has [committed to maintaining](https://blog.jetbrains.com/kotlin/2024/11/state-of-kotlin-scripting-2024/), and reuses JetBrains' supported `kotlin-main-kts` instead of re-inventing scripting host internals.

## Why does ktx need ~80 MB on disk?

About 60 MB of that is `kotlin-compiler-embeddable.jar`, the embedded Kotlin compiler. **Every** project that runs Kotlin scripts in-process (kotlin-main-kts, Kotlin Notebook, Gradle Kotlin DSL, kotlinx-jupyter, kscript) carries this same dependency. It is not avoidable as long as ktx can compile scripts itself.

If you do not need ktx's CLI on production hosts, use `ktx compile` to produce a 13 MB fat jar that runs on plain JRE 17+ with no Kotlin compiler at all.

## Can I share a JDK with my IDE / system Kotlin?

ktx-managed JDKs (under `~/.local/share/ktx/jdks/`) are independent from system Kotlin or IntelliJ-managed JDKs. They are downloaded fresh from Adoptium for ktx-controlled reproducibility.

If you already have a JDK and don't want ktx to download another, simply don't declare `@file:Toolchain(jdk = ...)` in your scripts; ktx will use whatever JDK is currently running it (the one you launched ktx with).

## Why does my first script run take so long?

The first run for a script is cold:

1. JVM startup (CLI process): ~150 ms (or ~80 ms with AppCDS).
2. Embedded Kotlin compiler init (PSI/FIR/IDEA platform classes): ~1.4 s.
3. Dependency resolution via Aether (network): variable, can be seconds.
4. Compilation: tens to hundreds of milliseconds.

Subsequent runs of the **same** script (compile cache hit) drop to ~280 ms because step 2-4 are skipped. Subsequent runs of **different** scripts in daemon mode (`ktx run --daemon`) drop to ~250 ms because step 2 is amortized across the daemon's lifetime.

## How does `--frozen` find jars?

The lockfile records jar paths relative to `~/.m2/repository/`. `--frozen` looks them up directly from disk; it does **not** call Aether or contact any remote.

If you use Gradle (which caches under `~/.gradle/caches/`), pre-populate `~/.m2` once via `mvn dependency:get` or run a non-frozen `ktx run` once to seed it. A future ktx version may also support Gradle cache layout natively.

## Does daemon mode use more memory?

Yes — a warm daemon JVM holds ~100–200 MB. That's the cost of keeping the compiler and PSI structures resident. Idle daemons exit after 30 minutes by default (`KTX_DAEMON_IDLE_TIMEOUT_MIN`), so the memory is reclaimed when you stop scripting.

If memory is precious, don't use `--daemon` and accept the cold start tax.

## Can I use ktx in CI?

Yes. The recommended pattern:

1. Locally: `ktx lock my-script.main.kts` and commit `my-script.main.kts.lock` alongside your script.
2. CI: cache `~/.m2/repository/` (or seed it via `mvn dependency:resolve` against the lockfile), then run `ktx run --frozen my-script.main.kts`.

Cold-startup cost in CI is ~1.6 s, no network access during the run.

## Can ktx run cross-platform?

Yes for the supported triples:

| OS | Architectures |
| --- | --- |
| macOS | aarch64, x64 |
| Linux | aarch64, x64 |
| Windows | x64 |

The codebase is plain JVM with `java.net.UnixDomainSocketAddress`. Windows 10 1803+ supports AF_UNIX natively. Other platforms have not been tested but should mostly work.

## What versions of Kotlin can scripts use?

ktx is built against Kotlin 2.2.0. The version is currently fixed: a script declaring `@file:Toolchain(kotlin = "2.3.0")` will cause ktx to error out (the multi-Kotlin-version routing path is on the [Phase 3 TODO](https://github.com/gaojunran/ktx/blob/main/reports/phase-3-todo.md)).

Practical impact: small. Scripts that use Kotlin 2.0+ features generally work; scripts pinned to a non-current Kotlin version are rare in practice.

## Can I customize the dependency repository?

Yes — the same `@file:Repository(...)` annotation that `kotlin-main-kts` supports works in ktx. Examples:

```kotlin
@file:Repository("https://maven.aliyun.com/repository/public")
@file:Repository("https://repo.maven.apache.org/maven2/")
@file:DependsOn("io.ktor:ktor-client-core-jvm:3.0.0")
```

Authentication-protected repositories are also supported via the same syntax `kotlin-main-kts` documents.

## How can I report bugs?

Open an issue on [GitHub](https://github.com/gaojunran/ktx/issues). Useful info to include:

- ktx version (`git rev-parse HEAD` from your build).
- OS and architecture.
- JDK version (`java -version`).
- Reproduction: a minimal script + the exact ktx command + observed output.
- For daemon issues: contents of `~/.cache/ktx/d/<dir>/log` (one log file per daemon instance).
