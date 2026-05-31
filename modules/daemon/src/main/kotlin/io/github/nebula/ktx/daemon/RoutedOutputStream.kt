package io.github.nebula.ktx.daemon

import java.io.OutputStream
import java.io.PrintStream

/**
 * 进程级 [PrintStream] 但内部按当前线程查 ThreadLocal sink。
 *
 * 用途：daemon 并发跑多个脚本时，每个 worker 线程要把 println 写到「自己
 * 那条 socket」上。直接 [System.setOut] 会被多线程互相覆盖，写错地方。
 *
 * 用法：daemon 启动时一次性 set 一对 [Routed] PrintStream 到 System.out /
 * System.err；每个 worker 线程进入时 [bind] 自己的 sink，离开时 [unbind]。
 * 没 bind 的线程（例如 daemon 自己的 logback、accept-loop）走 [fallback]
 * sink，通常指向真正的进程 stdout（或 /dev/null）。
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
        // 显式 close 进程级 PrintStream 不合理，忽略
    }
}
