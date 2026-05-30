package io.github.nebula.ktx.daemon

/**
 * daemon 进程入口。CLI fork 它出来时直接跑这个 main。
 *
 * Phase 2.1 不做 detach / setsid，保持子进程关系给 CLI 监管 ——
 * Phase 2.2 上独立 detach 让 daemon 在 CLI 退出后继续运行。
 */
object Bootstrap {
    @JvmStatic
    fun main(args: Array<String>) {
        DaemonServer().serve()
    }
}
