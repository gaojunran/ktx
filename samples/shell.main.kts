#!/usr/bin/env ktx

import kotlin.time.Duration.Companion.seconds

// Shell DSL is auto-imported in every ktx script.
// No @file:DependsOn needed — kotlinx.coroutines is bundled with the runner.

// ------------------------------------------------------------------
// Basic command execution
// ------------------------------------------------------------------

val whoami = sh("whoami").text().trim()
println("Running as: $whoami")

// ------------------------------------------------------------------
// Pipes — stdout of the left flows into stdin of the right
// ------------------------------------------------------------------

val files = (sh("ls") pipe sh("sort")).lines()
println("Files in this directory: $files")

// ------------------------------------------------------------------
// Working directory, environment, and timeout
// ------------------------------------------------------------------

val javaHome = sh("echo", RawArg("$JAVA_HOME"))
    .cwd("/tmp")
    .text()
    .trim()
println("JAVA_HOME from /tmp: $javaHome")

// ------------------------------------------------------------------
// Concurrency — run commands in parallel
// ------------------------------------------------------------------

val results = shAll(
    sh("uname", "-s"),
    sh("uname", "-r"),
)
println("OS: ${results[0].stdout.trim()}")
println("Kernel: ${results[1].stdout.trim()}")

// ------------------------------------------------------------------
// Retry — transient failures
// ------------------------------------------------------------------

val ok = retry(times = 3, delay = 1.seconds) {
    sh("echo", "retry succeeded").text()
}
println(ok.trim())
