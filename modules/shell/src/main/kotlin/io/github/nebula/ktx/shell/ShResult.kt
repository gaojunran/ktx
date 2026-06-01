package io.github.nebula.ktx.shell

import kotlinx.serialization.json.Json
import java.nio.charset.Charset
import kotlin.time.Duration

/**
 * Result of a shell command execution.
 */
class ShResult(
    val exitCode: Int,
    val stdoutBytes: ByteArray,
    val stderrBytes: ByteArray,
    val commandLine: String,
) {
    val stdout: String by lazy { stdoutBytes.toString(Charset.defaultCharset()) }
    val stderr: String by lazy { stderrBytes.toString(Charset.defaultCharset()) }

    fun lines(): List<String> = stdout.trimEnd().lines()

    inline fun <reified T> json(): T = Json.decodeFromString(stdout)

    override fun toString(): String =
        "ShResult(exitCode=$exitCode, command=$commandLine)"
}
