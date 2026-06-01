package io.github.nebula.ktx.shell

/**
 * Thrown when a shell command exits with a non-zero code.
 */
class ShellException(val result: ShResult) : RuntimeException(
    "Command exited with code ${result.exitCode}: ${result.commandLine}\n" +
            "stdout: ${result.stdout}\n" +
            "stderr: ${result.stderr}"
)

/**
 * Thrown when a command exceeds its timeout.
 */
class ShellTimeoutException(
    commandLine: String,
    timeout: kotlin.time.Duration,
) : RuntimeException("Command timed out after $timeout: $commandLine")
