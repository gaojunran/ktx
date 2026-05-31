package io.github.nebula.ktx.daemon

import java.io.InputStream

/**
 * 进程级 [InputStream] 但内部按当前线程查 ThreadLocal 源。
 *
 * 与 [RoutedOutputStream] 同思路：daemon 启动时一次性 [System.setIn] 一个
 * Routed 实例；每个 worker 线程进入 handleRun 时 [bind] 自己脚本的
 * stdin（通常是 PipedInputStream），离开时 [unbind]。没 bind 的线程
 * 走 [fallback]（默认空流，相当于 /dev/null）。
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
        // 进程级 System.in 不应被 close
    }
}
