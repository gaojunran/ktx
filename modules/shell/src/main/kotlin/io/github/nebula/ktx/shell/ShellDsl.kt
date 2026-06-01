package io.github.nebula.ktx.shell

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.time.Duration

// ------------------------------------------------------------------
// Command construction
// ------------------------------------------------------------------

/**
 * Create a shell command with automatic argument escaping.
 *
 * ```kotlin
 * val out = sh("echo", "hello").text()
 * val out2 = sh("mkdir", "Dir with spaces").text()
 * val out3 = sh("echo", RawArg("-n"), "hello").text()
 * ```
 */
fun sh(vararg parts: Any?): ShCommand {
    val formatted = parts.map { formatShellArg(it) }
    return ShCommand(formatted)
}

/**
 * Extension to create a shell command starting with this string as the command name.
 *
 * ```kotlin
 * val out = "echo".sh("hello").text()
 * ```
 */
fun String.sh(vararg args: Any?): ShCommand {
    val formatted = listOf(this) + args.map { formatShellArg(it) }
    return ShCommand(formatted)
}

private fun formatShellArg(arg: Any?): String = when (arg) {
    null -> ""
    is RawArg -> arg.value
    is Path -> ShellEscape.escape(arg.absolutePathString())
    is String -> ShellEscape.escape(arg)
    is Iterable<*> -> arg.joinToString(" ") { formatShellArg(it) }
    else -> ShellEscape.escape(arg.toString())
}

// ------------------------------------------------------------------
// Concurrency
// ------------------------------------------------------------------

/**
 * Execute multiple commands concurrently and return all results.
 *
 * Stdout and stderr are captured for each command so the returned [ShResult]
 * objects contain complete output.
 *
 * ```kotlin
 * val results = shAll(
 *     sh("sleep", "1"),
 *     sh("sleep", "2"),
 * )
 * ```
 */
suspend fun shAll(vararg commands: ShCommand): List<ShResult> =
    coroutineScope {
        commands.map { async { it.stdoutPipe().stderrPipe().execute() } }.awaitAll()
    }

// ------------------------------------------------------------------
// Retry
// ------------------------------------------------------------------

/**
 * Retry an action up to [times] with a fixed [delay] between attempts.
 *
 * ```kotlin
 * val result = retry(times = 5, delay = 5.seconds) {
 *     sh("cargo", "publish").execute()
 * }
 * ```
 */
suspend fun <T> retry(
    times: Int = 3,
    delay: Duration = Duration.ZERO,
    block: suspend () -> T,
): T {
    var lastException: Throwable? = null
    repeat(times) { attempt ->
        try {
            return block()
        } catch (e: Throwable) {
            lastException = e
            if (attempt < times - 1 && delay > Duration.ZERO) {
                delay(delay)
            }
        }
    }
    throw lastException ?: IllegalStateException("retry exhausted")
}
