# Daemon Mode

The default `ktx run` workflow forks a fresh JVM every time. Even with the on-disk compiled-script cache and AppCDS, you still pay ~150 ms of CLI startup plus ~1.4 s of embedded-Kotlin-compiler initialization whenever the cache misses.

Daemon mode keeps a long-lived JVM holding the initialized compiler. New script runs only pay the IPC + script-compile cost.

## Quick start

```bash
ktx run --daemon foo.kts
```

The first invocation forks a daemon. Subsequent invocations reuse it.

```bash
ktx daemon status   # is it up?
ktx daemon stop     # graceful shutdown
```

To make daemon mode the default for a shell session:

```bash
export KTX_DAEMON=1
```

## Performance

<script setup>
import { data } from '../.vitepress/theme/benchmarks.data.ts'
</script>

Three scenarios from the [full benchmark suite](/performance), filtered to the rows where the daemon makes the biggest difference:

<BenchChart
  :tools="data.tools"
  :rows="data.rows.filter(r => ['warm-no-deps', 'warm-with-deps', 'dev-loop'].includes(r.key))"
  :unit="data.unit"
  :versions="data.versions"
/>

The dev-loop row is the headline: editing a script and re-running it is roughly **6× faster** through the daemon than the same operation through the official `kotlin foo.main.kts`.

See the [Performance page](/performance) for the cold-start row (where ktx is intentionally not the fastest — the daemon's value is amortized warm runs, not one-shot CI invocations) and a fuller breakdown.

## How it works

```mermaid
sequenceDiagram
  participant CLI as ktx CLI
  participant FS as ~/.cache/ktx/d/&lt;key&gt;/
  participant D as Daemon JVM

  CLI->>FS: try connect to socket
  alt socket dead or missing
    CLI->>FS: acquire .start.lock (flock)
    CLI->>D: ProcessBuilder fork (java -cp ... Bootstrap)
    D->>FS: bind socket, write pid
    CLI->>D: connect (poll every 50 ms)
  end
  CLI->>D: RunRequest{script, args, ResolverMode}
  Note over D: routedOut/Err/In bind to this connection
  D->>D: ScriptRunner.run() -- compiler is already warm
  D-->>CLI: stream RunEvent.stdout / stderr
  D-->>CLI: RunEvent.exit{code}
  CLI->>CLI: exit(code)
```

## Per-JDK routing

Daemons are keyed by `(jdkMajor, protocolVersion)`. A script declaring `@file:Toolchain(jdk = "17")` causes ktx to look up the JDK 17 daemon (forking a new one if needed) instead of the default JDK daemon.

```bash
ktx daemon status --all
# daemon [jdk=21;protocol=v1]: running (pid=42631, dir=5f350a9ddd42)
# daemon [jdk=17;protocol=v1]: running (pid=42788, dir=6ab41020796c)
```

Each daemon has its own socket, AppCDS archive, and log file under its keyed directory.

## Lifecycle

- **Idle timeout**: a daemon exits after 30 minutes of inactivity (configurable via `KTX_DAEMON_IDLE_TIMEOUT_MIN`).
- **Heap watchdog**: at 80% heap occupancy (configurable via `KTX_DAEMON_HEAP_WARN_PCT`), the daemon logs a warning and runs `System.gc()`.
- **Crash recovery**: if the daemon process dies, the next `ktx run --daemon` notices the dead socket and forks a new daemon.
- **AppCDS**: each daemon writes its own `daemon.jsa` under its keyed directory on first run (`-XX:+AutoCreateSharedArchive`), cutting cold start from ~1.5 s to ~700 ms on subsequent forks.

## Concurrent invocations

Multiple `ktx run --daemon` calls are served in parallel by a `cachedThreadPool`. Each request gets its own worker thread; `RoutedOutputStream` and `RoutedInputStream` ensure a script's `println` and `readLine` go to the right client connection.

```bash
# 5 parallel runs on the same daemon — no contention beyond compiler internals.
for i in 1 2 3 4 5; do
  ktx run --daemon some-script.kts &
done
wait
```

## Streaming stdin

Daemon mode does not forward CLI stdin to the script by default. Reading stdin in the daemon would block the JVM shutdown waiting on a native syscall to unblock, adding ~300 ms to every exit.

To opt in for a single invocation:

```bash
echo "hello" | ktx run --daemon --forward-stdin my-script.kts
```

To enable globally:

```bash
export KTX_FORWARD_STDIN=1
```

## When daemon mode is not the right choice

- **Single-shot usage in CI**: a CI job runs the script once and exits. Use `--frozen` (no daemon) instead. Lockfile-frozen runs are already fast (~300 ms cold), and you avoid the daemon-fork overhead.
- **`ktx compile`**: the compile path does not use the daemon (it needs precise control over the temp cache directory).
