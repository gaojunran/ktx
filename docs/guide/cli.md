# CLI Reference

## Top-level

```
ktx [<options>] <command> [<args>]
```

If the first positional argument is a path to an existing file, `ktx` injects `run`:

```bash
ktx hello.kts        # equivalent to: ktx run hello.kts
```

If the first argument is `-e` or `--expr`, `ktx` injects `eval`.

### Global flags

| Flag | Description |
|---|---|
| `-h`, `--help` | Show help. |

## `ktx run`

```
ktx run [<options>] <SCRIPT> [<ARGS>...]
```

Run a Kotlin script. `<SCRIPT>` is a file path or `-` to read source from stdin.

### Options

| Flag | Default | Description |
|---|---|---|
| `--frozen` | off | Resolve dependencies only from `<script>.kts.lock`. Errors out if no lockfile is present. CI-friendly. |
| `--lock` | off | Resolve online and write/refresh `<script>.kts.lock` before running. Mutually exclusive with `--frozen`. |
| `--daemon` | off (or env `KTX_DAEMON=1`) | Run via the ktx daemon. Forks a background daemon if not already running. |
| `--forward-stdin` | off (or env `KTX_FORWARD_STDIN=1`) | When in daemon mode, stream the CLI's own stdin to the script. Off by default because the pump thread adds ~300 ms to process exit. |

### Examples

```bash
# Plain run (network access for first-time deps)
ktx run hello.kts arg1 arg2

# Read script source from stdin
echo 'println("from stdin")' | ktx run -

# Reproducible offline run
ktx run --frozen jackson.main.kts

# Refresh lockfile and run online
ktx run --lock jackson.main.kts

# Warm-daemon mode
ktx run --daemon foo.kts
```

## `ktx eval` / `ktx -e`

```
ktx eval -e "<EXPRESSION>" [<ARGS>...]
ktx -e "<EXPRESSION>"
```

Evaluate a Kotlin expression or statement directly.

```bash
ktx -e 'println(2 + 3)'
ktx -e 'val n = (1..100).sum(); println(n)'
```

## `ktx lock`

```
ktx lock <SCRIPT>
```

Resolve all `@file:DependsOn` declarations and write `<script>.kts.lock`. Does not execute the script body.

```bash
ktx lock my-script.main.kts
ls my-script.main.kts.lock
```

## `ktx add`

```
ktx add <COORDINATES> <SCRIPT> [--no-lock]
```

Insert a `@file:DependsOn(...)` line into the script's `@file:` header block, then refresh the lockfile (unless `--no-lock`).

```bash
ktx add io.ktor:ktor-client-core-jvm:3.2.0 client.main.kts
```

The annotation is inserted after any existing `@file:` lines, before the first non-annotation code.

## `ktx compile`

```
ktx compile <SCRIPT> [-o <OUTPUT>]
```

Compile the script to a standalone fat jar that runs on plain JRE 17+.

```bash
ktx compile hello.kts                # produces hello.jar next to the source
ktx compile foo.kts -o /tmp/foo.jar  # custom output path
java -jar hello.jar foo bar          # run with a JRE
```

The produced jar excludes the Kotlin compiler, the scripting host, and ktx's own CLI/daemon modules. It includes:

- the compiled script class
- `kotlin-stdlib` + `kotlin-script-runtime` + `kotlin-scripting-jvm` + `kotlin-scripting-common` + `kotlin-reflect`
- the ktx core jar (for the `KtsScript` base class)
- every transitive jar resolved from `@file:DependsOn`

A typical output is **11–13 MB**. See [Compiling Scripts](./compile) for details.

## `ktx toolchain`

```
ktx toolchain list
ktx toolchain install <MAJOR>
ktx toolchain path <MAJOR>
```

Manage Adoptium JDK installations under `~/.local/share/ktx/jdks/`.

```bash
ktx toolchain install 17       # download Temurin JDK 17 for current OS/arch
ktx toolchain list             # show installed versions
ktx toolchain path 17          # print absolute path to bin/java
```

A script can then declare:

```kotlin
@file:Toolchain(jdk = "17")
```

ktx will check the requested major version, install it on demand, and re-exec itself with `JAVA_HOME` pointed at that JDK before compilation.

## `ktx daemon`

```
ktx daemon status [--all] [--jdk <MAJOR>]
ktx daemon stop   [--all] [--jdk <MAJOR>]
```

Inspect or stop daemon instances.

```bash
ktx daemon status                   # current-JDK daemon
ktx daemon status --all             # all daemons across JDK versions
ktx daemon stop --jdk 17            # stop only the JDK-17 daemon
ktx daemon stop --all               # stop everything
```

Daemon instances are stored under `~/.cache/ktx/d/<key12>/`, keyed by `(jdkMajor, protocolVersion)`.

### Environment variables

| Variable | Default | Effect |
|---|---|---|
| `KTX_DAEMON` | unset | Set to `1` to default `ktx run` to `--daemon` mode. |
| `KTX_FORWARD_STDIN` | unset | Set to `1` to default `--forward-stdin` on. |
| `KTX_DAEMON_IDLE_TIMEOUT_MIN` | `30` | Daemon self-exits after this many minutes of inactivity. |
| `KTX_DAEMON_HEAP_WARN_PCT` | `80` | Daemon logs a warning and triggers `System.gc()` past this heap occupancy. |
