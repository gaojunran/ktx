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

## Further reduction (planned)

| Technique | Phase | Expected size |
| --- | --- | --- |
| Current (Phase 2.4) | shipped | 13 MB |
| ProGuard / R8 reflect-only keeps | Phase 3+ | ~10 MB |
| `jpackage` + jlink runtime image | Phase 3.1 | 50 MB total app, 0 user dependencies |
| GraalVM native image | Phase 3.2 | ~10 MB native binary, ~10 ms startup |

The `--self-contained` and `--native` flags are tracked in the [Phase 3 TODO](https://github.com/gaojunran/ktx/blob/main/reports/phase-3-todo.md).
