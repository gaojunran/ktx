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

## Inspecting daemon logs

Each daemon writes lifecycle events (start, idle-timeout shutdown, GC warnings) to its own log file under the keyed directory:

```bash
ktx daemon logs               # last 100 lines of the current-JDK daemon
ktx daemon logs --tail 20     # last 20 lines
ktx daemon logs --tail 0      # full log
ktx daemon logs --all         # every daemon, side by side
ktx daemon logs --jdk 17      # specifically the JDK 17 daemon
ktx daemon logs --path        # just print the log path; pipe into tail -f or less
```

A common pattern for following a live daemon:

```bash
tail -f "$(ktx daemon logs --path)"
```

Note: the bundled `kotlin-main-kts` jar transitively ships a `slf4j-simple` binding which can win SLF4J's provider lottery on classpath order, in which case the daemon's logs go to the parent's stderr (and are then discarded by the fork) rather than to the file. Tracked as a follow-up; until then `--path` will still print the canonical location even when the file is empty.

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

Daemon mode does not forward CLI stdin to the script by default. The default makes `ktx run --daemon` exit immediately when the script does, even if the parent shell has a long-running pipe still attached.

To opt in for a single invocation:

```bash
echo "hello" | ktx run --daemon --forward-stdin my-script.kts
```

To enable globally:

```bash
export KTX_FORWARD_STDIN=1
```

When `--forward-stdin` is on, the CLI spawns a pump thread that reads from `System.in` and ships the bytes to the daemon as stdin frames. The pump thread is a daemon thread; on script exit the CLI tears it down and exits, so a stdin pipe that never reaches EOF will not hang the CLI process itself (though the parent shell's pipeline may still wait on the writer).

## When daemon mode is not the right choice

- **Single-shot usage in CI**: a CI job runs the script once and exits. Use `--frozen` (no daemon) instead. Lockfile-frozen runs are already fast (~300 ms cold), and you avoid the daemon-fork overhead.
- **`ktx compile`**: the compile path does not use the daemon (it needs precise control over the temp cache directory).
