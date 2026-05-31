# Performance

<script setup>
import { data } from './.vitepress/theme/benchmarks.data.ts'
</script>

ktx is benchmarked end-to-end against the official `kotlin` CLI (which dispatches `.main.kts` through the same `kotlin-main-kts` host that ktx wraps) using [`hyperfine`](https://github.com/sharkdp/hyperfine) on the same scripts under identical conditions.

Three configurations are compared in every scenario:

- **`ktx-daemon`** — `ktx run --daemon`, the recommended dev workflow. The daemon keeps the embedded Kotlin compiler resident, so subsequent runs only pay the script-compile + class-load cost.
- **`ktx`** — `ktx run` without the daemon. Cold JVM each invocation, but with ktx's compiled-script cache and AppCDS archive both warm.
- **`main-kts`** — system `kotlin foo.main.kts`. The straightforward way most people run `.kts` today.

::: tip Hardware
All numbers below were measured on Apple M3 (8-core), Temurin JDK 21.0.11, with each tool's caches warmed once before timing.
Reproduce locally with `mise run bench` (script in [`benchmarks/`](https://github.com/gaojunran/ktx/tree/main/benchmarks)).
:::

## Comparison vs `main-kts`

<BenchChart
  :tools="data.tools"
  :rows="data.rows"
  :unit="data.unit"
  :versions="data.versions"
/>

<small>Last updated: {{ new Date(data.updated).toISOString().slice(0, 10) }} · Source: [`benchmarks/results.json`](https://github.com/gaojunran/ktx/blob/main/benchmarks/results.json)</small>

## What the rows show

### Warm — hello.kts (no deps)

The simplest possible script; the goal is to isolate JVM-startup + script-execution overhead with everything else cached.

- ktx-daemon ≈ **{{ data.rows.find(r => r.key === 'warm-no-deps').values['ktx-daemon'] }} ms** — pure IPC cost, no compiler cold start.
- ktx (no daemon) ≈ **{{ data.rows.find(r => r.key === 'warm-no-deps').values.ktx }} ms** — JVM start + AppCDS-warm class load + script compile cache hit.
- `main-kts` ≈ **{{ data.rows.find(r => r.key === 'warm-no-deps').values['main-kts'] }} ms** — JVM start + full scripting host init even with cache hit.

### Warm — script with `@file:DependsOn`

Same protocol but the script imports `jackson-databind`. All three tools have the dependency in their local Maven cache.

The gap holds: ktx-daemon stays at ~170 ms, while `main-kts` still has to walk through scripting-host init even when the compiled-script cache is hot.

### Dev loop — script edited every run

The hardest case: every run sees a fresh script source, so the on-disk compiled-script cache misses every time. This is the real-world experience of editing a script and pressing Enter.

This row is the headline. ktx-daemon stays around **300 ms** because the compiler is already warm in the daemon JVM; ktx without a daemon and `main-kts` both pay the full ~1.4 s embedded-Kotlin-compiler initialization on every invocation.

### Cold start — caches cleared

Adversarial baseline: every cache (`~/.cache/ktx/compiled`, `~/.cache/main.kts.compiled.cache`, ktx daemons) is wiped before each run.

`main-kts` actually wins this row. ktx itself ships clikt + tomlj + okhttp + protobuf + a routing layer for the daemon path, all of which the JVM has to load even when not used. On a cold boot that overhead is real. Daemon mode is irrelevant here because it isn't even running.

The daemon's cold-fork case (~700 ms with AppCDS warm, ~2.1 s on first-ever fork) is paid **once**, not per script — see [Daemon Mode](./guide/daemon) for the lifecycle details.

## Where the time goes

Inside `ktx-daemon` the ~120 ms warm run breaks down roughly:

- **CLI front-end JVM start + class load** — ~80 ms (AppCDS-warm).
- **IPC round trip + arg parsing** — ~10 ms.
- **Script class load + execution** — ~30 ms.

For a fresh script (compile cache miss):

- CLI startup — ~80 ms.
- IPC + arg parsing — ~10 ms.
- **Script compilation (warm compiler)** — ~130 ms.
- Script class load + execution — ~30 ms.

Compare to `main-kts` on the same warm script: a fresh JVM has to redo the embedded-compiler init (~1.4 s, not visible because most of it overlaps with class loading the JVM was doing anyway), so the steady-state cost stays in the 700–800 ms range no matter how many times you run it.

## When ktx is *not* faster

The cold-start row above is honest about this: when every cache is empty and the daemon isn't running, ktx loses to `main-kts` because ktx's CLI carries more code. Two takeaways:

- For **one-off scripts in CI on every run**, the lockfile + frozen-mode path narrows the gap drastically. See [Lockfiles](./guide/lockfile).
- For **frequently-run scripts (dev loop, cron jobs)**, the daemon's amortization wins overwhelmingly.

## Reproducing

```bash
# Build ktx
mise run build
export PATH="$PWD/modules/cli/build/install/ktx/bin:$PATH"

# Install hyperfine if missing
brew install hyperfine     # or `mise install hyperfine`

# Run the same suite documented above
mise run bench
```

The script at [`benchmarks/bench.sh`](https://github.com/gaojunran/ktx/blob/main/benchmarks/bench.sh) drives `hyperfine` over four scenarios and writes [`benchmarks/results.json`](https://github.com/gaojunran/ktx/blob/main/benchmarks/results.json), which the chart on this page reads at build time.
