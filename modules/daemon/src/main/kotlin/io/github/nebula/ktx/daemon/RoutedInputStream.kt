package io.github.nebula.ktx.daemon

import java.io.InputStream

/**
 * Process-wide [InputStream] that internally dispatches to a per-thread
 * source via ThreadLocal.
 *
 * Same idea as [RoutedOutputStream]: at daemon startup, [System.setIn] a
 * single Routed instance. Each worker thread entering handleRun [bind]s the
 * stdin for its own script (typically a PipedInputStream) and [unbind]s on
 * exit. Threads without a binding read from [fallback] (default: the empty
 * stream, equivalent to /dev/null).
 */
class RoutedInputStream(
    private val fallback: InputStream = InputStream.nullInputStream(),
) : InputStream() {

    private val tl = ThreadLocal<InputStream?>()

    fun bind(source: InputStream) {
        tl.set(source)
    }

    fun unbind() {
        tl.remove()
    }

    private fun current(): InputStream = tl.get() ?: fallback

    override fun read(): Int = current().read()
    override fun read(b: ByteArray, off: Int, len: Int): Int = current().read(b, off, len)
    override fun available(): Int = current().available()
    override fun close() {
        // The process-wide System.in must not be closed.
    }
}
