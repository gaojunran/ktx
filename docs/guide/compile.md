# Compiling Scripts

`ktx compile` packages a script into a standalone fat jar that runs on plain JRE 17+. End users do not need ktx, do not need a Kotlin compiler, do not need any extra setup.

## Quick example

```bash
$ ktx compile samples/hello.main.kts
[INFO] compile /path/to/samples/hello.main.kts
[INFO] merge: 3 KB script artifact + 0 user deps + 4 Kotlin runtime jars + ktx core
[INFO] pack done: samples/hello.jar (7501 entries)
Output: samples/hello.jar (11288 KB)
Run:    java -jar samples/hello.jar [args...]

$ java -jar samples/hello.jar foo bar
hello from main.kts
args (2):
  [0] foo
  [1] bar
```

## What's in the jar

The merged jar contains exactly the classes needed at runtime:

```mermaid
graph TD
    classDef in fill:#e3f2fd,stroke:#1565c0;
    classDef out fill:#fce4ec,stroke:#c2185b,stroke-dasharray: 5 3;

    A[Compiled script class<br/>e.g. Hello_main.class]
    B[kotlin-stdlib]
    C[kotlin-script-runtime]
    D[kotlin-scripting-jvm<br/>RunnerKt]
    E[kotlin-scripting-common<br/>ScriptCompilationConfig]
    F[kotlin-reflect]
    G[ktx core jar<br/>KtsScript base class]
    H[Every @file:DependsOn<br/>jar + transitives]

    X[kotlin-compiler-embeddable<br/>~60 MB]
    Y[main-kts.jar]
    Z[ktx cli/daemon/protocol]

    A:::in --> P((fat jar))
    B:::in --> P
    C:::in --> P
    D:::in --> P
    E:::in --> P
    F:::in --> P
    G:::in --> P
    H:::in --> P

    X:::out -.->|excluded| P
    Y:::out -.->|excluded| P
    Z:::out -.->|excluded| P
```

This separation is what brings the size down to **11–13 MB** instead of ktx's full 80 MB install.

## Sizes and startup

| Script | Output size | `java -jar` startup |
| --- | ---: | ---: |
| `hello.main.kts` (no deps) | 11 MB | ~180 ms |
| `jackson.main.kts` (`jackson-databind` + 3 transitives) | 13 MB | ~220 ms |

vs. `ktx run` first invocation of `jackson.main.kts`: 6.7 s.

```mermaid
%%{init: {'theme':'default'}}%%
graph LR
    A[Source .kts] -->|6.7s first ktx run| B[output via ktx]
    A -->|6.5s ktx compile| C[fat jar 13 MB]
    C -->|220ms java -jar| D[output without ktx]
```

The compile cost lands once, on your build machine. End users pay only the `java -jar` cost.

## Side effects

`ktx compile` runs the script body once during compilation, because dependency resolution in `kotlin-main-kts` is interleaved with class generation. If your script has side effects you don't want to fire during build (HTTP calls, file writes, etc.), wrap them in a `main` function or guard them with an explicit "compile mode" flag.

```kotlin
// avoid: this network call fires during ktx compile
val data = HttpClient().get("https://api.example.com").body<String>()
println(data)

// prefer: keep side effects under main
fun main() {
    val data = HttpClient().get("https://api.example.com").body<String>()
    println(data)
}
main()
```

Future versions may add a `--no-execute` flag that skips evaluation entirely; tracked in the [Phase 3 TODO](https://github.com/gaojunran/ktx/blob/main/reports/phase-3-todo.md).

## What gets included automatically

`ktx compile` analyzes your script's `@file:DependsOn` and `@file:Repository` declarations the same way `ktx run` does. Every transitive jar that resolution produces is packaged.

Resources (non-class files in jars) are also packaged: `META-INF/services/*` files are concatenated for ServiceLoader compatibility, `module-info.class` files are dropped to keep the jar usable on the classpath.

## What is not included

- **`kotlin-compiler-embeddable.jar`** (~60 MB). The script is already compiled; the runtime needs no compiler.
- **`main-kts.jar`**. Its sole purpose is to drive resolution and compilation; both happen at compile time.
- **ktx's CLI / daemon / protocol modules**.

This is what gives `ktx compile` output its 1/6 size advantage.

## When to use `ktx compile`

- **Distribute internal tools.** Hand a coworker a 13 MB jar instead of asking them to install ktx and Kotlin.
- **Build artifacts in CI.** Embed `ktx compile` in a build pipeline; ship the jar with your release.
- **One-off scripts on production.** Schedule a cron job that runs `java -jar your-script.jar`; no ktx footprint on the host.

## When not to use it

- **Active development.** Use `ktx run --daemon` for the dev loop; recompile-to-jar every iteration is overkill.
- **Scripts that change every run.** `ktx compile` does not run a daemon; it shells out a fresh compiler per invocation.

## Self-contained: zero Java required

`--self-contained` wraps the fat jar in a directory that ships its own minimal JRE. End users do not need Java installed at all — they extract the directory and run `bin/<name>`.

```bash
$ ktx compile samples/hello.main.kts --self-contained -o ./hello-app
[INFO] compile /path/to/samples/hello.main.kts
[INFO] modules: detected=[java.base, java.logging, ...], final=[java.base, ...]
artifact: ./hello-app/  (~42 MB)
layout:
  bin/hello-app    launcher
  lib/app.jar      compiled script (~11 MB)
  runtime/         embedded JRE (~31 MB)
run with: ./hello-app/bin/hello-app [args...]

$ ./hello-app/bin/hello-app foo bar
hello from main.kts
args (2):
  [0] foo
  [1] bar
```

Under the hood:

1. Build the fat jar (same as the default path above).
2. `jdeps --print-module-deps` reads the fat jar and reports the JDK modules it actually uses.
3. `jlink --add-modules <list> --compress=2 --strip-debug` produces a minimal runtime under `runtime/`.
4. A small launcher script (`/bin/sh` on macOS / Linux, `.bat` on Windows) chains the bundled `java` to `lib/app.jar`.

A few extra modules — `jdk.unsupported`, `java.naming`, `java.logging` — are unioned in unconditionally because `jdeps` cannot trace classes loaded reflectively, and they are cheap to keep.

### Trade-offs

- **Size**: ~40 MB for a no-deps script, ~60 MB once a database driver or large JSON library lands. About 4× the fat jar but absolutely flat for the end user.
- **Host platform only**: `jlink` cannot cross-compile. To distribute to macOS, Linux, and Windows, run `ktx compile --self-contained` once per platform.
- **Build host needs a JDK**: `jdeps` and `jlink` ship with the JDK, not the JRE. ktx will exit with a clear error if either is missing.

### When to use it

- Tools for non-developer audiences who balk at "first install Java".
- Locked-down hosts where you cannot install or update a system JRE.
- Reproducible artifacts: the JRE version is pinned to whatever JDK ran `ktx compile`.

## Native binary: zero Java, single file

`--native` produces a single executable file via GraalVM `native-image`. End users get one binary they double-click or `chmod +x` and run; no JRE, no fat jar, nothing else.

```bash
$ ktx compile samples/hello.main.kts --native --collect-metadata
compiling hello.main.kts -> samples/hello (native binary)
note: --collect-metadata will execute the script once under JVM agent before native-image runs.
[INFO] native-image: /Users/.../bin/native-image
[INFO] fat jar built: /tmp/.../app.jar (11315 KB)
[INFO] running fat jar under native-image-agent (this executes the script body)...
hello from main.kts
[INFO] script metadata persisted to samples/hello.main.kts.native-meta
[INFO] native-image build (this takes 30-180s)...
... <native-image progress bar> ...
artifact: samples/hello (~38 MB)
run with: samples/hello [args...]

$ time ./samples/hello arg1 arg2
hello from main.kts
args (2):
  [0] arg1
  [1] arg2
real    0m0.022s
```

### How `--collect-metadata` works

GraalVM analyses your fat jar at build time and emits a binary that excludes everything it cannot prove reachable. Reflection, dynamic class loading, JDK serialisation and resource lookups are invisible to that analysis, so they need to be declared explicitly via JSON metadata. ktx supplies three layers:

1. **Built-in base** — covers the Kotlin scripting host's own reflection paths (`KtsScript`, `KJvmCompiledScript`, `META-INF/services/...`, `kotlin_builtins` resources). Always applied.
2. **GraalVM Reachability Metadata Repository (GRMR) subset** — bundled inside ktx for popular libraries like jackson. When your script declares `@file:DependsOn("com.fasterxml.jackson.core:jackson-databind:...")`, ktx finds the matching entry and feeds it to native-image automatically.
3. **Per-script trace** — `--collect-metadata` runs the fat jar once on the JVM with `native-image-agent` attached, capturing every reflection / resource / serialisation event. The recording lands in `<script>.native-meta/` next to the source. **Subsequent builds re-use that directory** without re-running the agent.

Once `<script>.native-meta/` exists, `ktx compile --native <script>` (no flag) is sufficient — and the directory is safe to commit to source control to make builds deterministic across machines.

### What gets distributed

A native binary is **completely self-contained**. The metadata JSON only matters at build time; everything reachable is compiled into the binary itself. End users receive nothing but the binary and run it.

| Artifact | Required to run | Size |
| --- | --- | ---: |
| Fat jar (default) | JRE 17+ | ~11–13 MB |
| Self-contained dir | nothing | ~40–60 MB |
| **Native binary (`--native`)** | **nothing** | **~38–41 MB** |

### Trade-offs

- **Build cost is high**: 30–180 s on a modern laptop, peak RSS ~3 GB. Don't run `--native` on every code change. Use it for releases.
- **Host platform only**: like `jlink`, `native-image` does not cross-compile. To distribute to macOS, Linux, and Windows, run `ktx compile --native` once per platform (typically in CI matrices).
- **Build host needs GraalVM**: install via `mise use -g java@oracle-graalvm-21.0.7` (recommended) or any other GraalVM 21+ distribution. ktx looks for `native-image` under `$GRAALVM_HOME/bin`, then `$JAVA_HOME/bin`, then on `PATH`.
- **`--no-fallback` is mandatory**: ktx always passes `--no-fallback` so missing reflection metadata fails the build instead of producing a bloated fallback image. Re-run with `--collect-metadata` if a previously unrecorded reflection path appears.
- **Side effects fire during `--collect-metadata`**: the agent runs your script body to record what it does. If the script makes HTTP calls or mutates state, that happens once at build time — same caveat as the fat-jar `compile` flow.

### When to use it

- Distributing CLI tools to end users who don't have any Java setup at all.
- Performance-critical short-lived scripts where the ~10 ms startup of a native binary matters versus ~200 ms for `java -jar`.
- Embedding scripts inside container images where size and start-time both matter.

### When *not* to use it

- During development. Fat jar (`ktx compile`) or daemon mode (`ktx run --daemon`) are dramatically faster for the dev loop.
- For scripts whose reflection surface changes often. The `--collect-metadata` recording captures one execution path; if your script branches on data and calls reflection conditionally, run `--collect-metadata` enough times to cover every branch (the agent merges runs in `<script>.native-meta/`).

## Further reduction (planned)

| Technique | Phase | Expected size |
| --- | --- | --- |
| Fat jar (Phase 2.4) | shipped | 13 MB |
| `--self-contained` (Phase 3.1) | shipped | 40-60 MB total, zero user deps |
| **`--native` (Phase 3.2)** | **shipped** | **~38-41 MB native binary, ~10-25 ms startup** |
| ProGuard / R8 reflect-only keeps | Phase 3+ | ~10 MB |
| `jpackage` installer (.dmg / .deb / .exe) | Phase 3+ | wraps `--self-contained` |

The `jpackage` installer mode is tracked in the [Phase 3 TODO](https://github.com/gaojunran/ktx/blob/main/reports/phase-3-todo.md).
