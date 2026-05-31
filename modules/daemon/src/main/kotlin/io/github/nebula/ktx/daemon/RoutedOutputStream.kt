package io.github.nebula.ktx.daemon

import java.io.OutputStream
import java.io.PrintStream

/**
 * Process-wide [PrintStream] that internally dispatches to a per-thread sink
 * via ThreadLocal.
 *
 * Why: when the daemon runs multiple scripts concurrently, each worker
 * thread must direct its println output to "its own" socket. A plain
 * [System.setOut] would let threads clobber each other and write to the
 * wrong place.
 *
 * Usage: at daemon startup, install a pair of [Routed] PrintStreams as
 * System.out / System.err once. Each worker thread [bind]s its own sink on
 * entry and [unbind]s on exit. Threads without a binding (e.g. the daemon's
 * own logback, accept-loop) write to the [fallback] sink, typically the
 * actual process stdout (or /dev/null).
 */
class RoutedOutputStream(
    private val fallback: OutputStream,
) : OutputStream() {

    private val tl = ThreadLocal<OutputStream?>()

    fun bind(sink: OutputStream) {
        tl.set(sink)
    }

    fun unbind() {
        tl.remove()
    }

    private fun current(): OutputStream = tl.get() ?: fallback

    override fun write(b: Int) = current().write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = current().write(b, off, len)
    override fun flush() = current().flush()
    override fun close() {
        // Explicitly closing a process-wide PrintStream is wrong; ignore.
    }
}
