package io.github.nebula.ktx.shell

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * A spawned child process for streaming access.
 *
 * ```kotlin
 * val child = sh"long-running-cmd".spawn()
 * child.stdout.lineFlow().collect { println(it) }
 * val result = child.await()
 * ```
 */
class ShChild internal constructor(
    private val process: Process,
    val commandLine: String,
) {
    val stdout: InputStream get() = process.inputStream
    val stderr: InputStream get() = process.errorStream

    suspend fun await(): ShResult = withContext(Dispatchers.IO) {
        process.waitFor()
        ShResult(
            exitCode = process.exitValue(),
            stdoutBytes = process.inputStream.use { it.readAllBytes() },
            stderrBytes = process.errorStream.use { it.readAllBytes() },
            commandLine = commandLine,
        )
    }

    fun kill(signal: String = "TERM") {
        if (signal == "KILL" || signal == "9") {
            process.destroyForcibly()
        } else {
            process.destroy()
        }
    }

    val isAlive: Boolean get() = process.isAlive
}
