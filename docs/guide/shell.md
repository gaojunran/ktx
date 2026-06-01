# Shell Scripting

Every `.kts` script executed by ktx has a built-in shell DSL inspired by [dax](https://github.com/dsherret/dax). You can run shell commands, pipe them together, and manage I/O without leaving Kotlin syntax.

## Basic commands

```kotlin
val out = sh("echo", "hello").text()
println(out) // hello

val lines = sh("ls", "-1").lines()
println(lines)

val code = sh("false").ignoreExitCode().exitCode()
println(code) // 1
```

The [sh] function escapes all arguments automatically. If you need raw (unescaped) arguments, wrap them in [RawArg]:

```kotlin
val out = sh("printf", RawArg("%s"), "hello").text()
println(out) // hello
```

## String extension

```kotlin
val out = "echo".sh("hello").text()
```

## Pipes

Pipe stdout of one command into stdin of another with the [pipe] infix operator:

```kotlin
val result = (sh("echo", "foo bar baz") pipe sh("grep", "bar")).text()
println(result) // foo bar baz
```

## Working directory and environment

```kotlin
sh("pwd")
    .cwd("/tmp")
    .env("KEY", "value")
    .text()
```

## Timeouts

```kotlin
sh("sleep", "5")
    .withTimeout(3.seconds)
    .execute() // throws ShellTimeoutException
```

## Concurrency

Run multiple commands in parallel and collect their results:

```kotlin
val results = shAll(
    sh("uname", "-s"),
    sh("uname", "-r"),
)
println("OS: ${results[0].stdout.trim()}")
println("Kernel: ${results[1].stdout.trim()}")
```

Each result is a [ShResult] with `exitCode`, `stdout`, `stderr`, and `lines()`.

## Retry

```kotlin
val result = retry(times = 5, delay = 1.seconds) {
    sh("curl", "-fsSL", "https://example.com/api").text()
}
```

## I/O control

| Method | Behaviour |
|--------|-----------|
| `.text()` | Return stdout as a `String` (auto-captures) |
| `.lines()` | Return stdout as `List<String>` |
| `.bytes()` | Return stdout as `ByteArray` |
| `.exitCode()` | Return exit code (does not throw on non-zero) |
| `.quiet()` | Suppress stdout and stderr |
| `.stdoutPipe()` / `.stderrPipe()` | Explicitly capture streams |
| `.stdout(file)` / `.stderr(file)` | Redirect to file |
| `.stdin(text)` / `.stdin(file)` | Feed stdin from string or file |

## Error handling

By default any non-zero exit code throws [ShellException], whose `.result` exposes the full [ShResult].

```kotlin
try {
    sh("git", "push").execute()
} catch (e: ShellException) {
    println("Command failed with exit code ${e.result.exitCode}")
    println("stderr: ${e.result.stderr}")
}
```

## Complete example

```kotlin
#!/usr/bin/env ktx

val whoami = sh("whoami").text().trim()
println("Running as: $whoami")

val files = (sh("ls") pipe sh("sort")).lines()
println("Files: $files")

val results = shAll(
    sh("uname", "-s"),
    sh("uname", "-r"),
)
println("OS: ${results[0].stdout.trim()}")
println("Kernel: ${results[1].stdout.trim()}")

val ok = retry(times = 3, delay = 1.seconds) {
    sh("echo", "retry succeeded").text()
}
println(ok.trim())
```

Save as `shell.main.kts` and run:

```bash
ktx shell.main.kts
```
