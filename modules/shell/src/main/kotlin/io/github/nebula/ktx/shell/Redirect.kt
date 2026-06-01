package io.github.nebula.ktx.shell

import java.nio.file.Path

/**
 * Represents an I/O redirect for stdin/stdout/stderr.
 */
sealed class Redirect {
    object Inherit : Redirect()
    object Null : Redirect()
    object Capture : Redirect()
    object Pipe : Redirect()
    data class FileOut(val path: Path) : Redirect()
    data class FileIn(val path: Path) : Redirect()
    data class StringIn(val text: String) : Redirect()
    data class PipeFrom(val upstream: ShCommand) : Redirect()
}
