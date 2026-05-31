package io.github.nebula.ktx.daemon

import java.nio.file.Path

/**
 * daemon 进程入口。
 *
 * CLI fork daemon 时通过环境变量 `KTX_DAEMON_DIR` 指定 daemon 目录（路由后
 * 的 `~/.cache/ktx/d/<key12>/`）。没设就用 [DaemonPaths.defaultDaemonDir]
 * （兼容 Phase 2.1 直接 `java ...Bootstrap` 的玩法，例如调试时手动启）。
 *
 * 启动时 set 系统属性 `ktx.daemon.dir`，给 logback 配置里 `${ktx.daemon.dir}/log`
 * 占位符用 —— 这样不同 daemon 实例日志各写各的。
 */
object Bootstrap {
    @JvmStatic
    fun main(args: Array<String>) {
        val daemonDir = System.getenv("KTX_DAEMON_DIR")
            ?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it) }
            ?: DaemonPaths.defaultDaemonDir()
        System.setProperty("ktx.daemon.dir", daemonDir.toAbsolutePath().toString())
        DaemonServer(daemonDir).serve()
    }
}
