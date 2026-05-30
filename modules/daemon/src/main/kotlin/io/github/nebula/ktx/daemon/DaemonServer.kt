package io.github.nebula.ktx.daemon

import com.google.protobuf.ByteString
import io.github.nebula.ktx.core.exec.ScriptRunner
import io.github.nebula.ktx.proto.v1.ExitEvent
import io.github.nebula.ktx.proto.v1.Frames
import io.github.nebula.ktx.proto.v1.PROTOCOL_VERSION
import io.github.nebula.ktx.proto.v1.RequestEnvelope
import io.github.nebula.ktx.proto.v1.ResponseEnvelope
import io.github.nebula.ktx.proto.v1.RunEvent
import io.github.nebula.ktx.proto.v1.RunRequest
import io.github.nebula.ktx.proto.v1.ShutdownResponse
import io.github.nebula.ktx.proto.v1.StatusResponse
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.io.PrintStream
import java.lang.management.ManagementFactory
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * ktx daemon 服务端。
 *
 * Phase 2.1 最小实现：
 *   - Unix domain socket 监听 `~/.cache/ktx/d/socket`
 *   - 串行处理客户端请求（一次一个）
 *   - 唯一支持的 RPC：Run（执行脚本，stdout/stderr 流式回传）
 *   - 加载一次 [ScriptRunner]，常驻 —— 这是 daemon 价值所在
 *
 * 后续阶段补：
 *   - Phase 2.2：并发处理、空闲超时、heap watchdog、toolchain 路由
 *   - Phase 2.3：多 Kotlin 编译器版本支持（每个 daemon 绑一个编译器版本）
 *
 * stdout/stderr 透传策略：脚本运行期间通过 [System.setOut] / [System.setErr]
 * 把 PrintStream 替换成「写到客户端 socket」的版本。脚本结束后立刻还原。
 * **不做**真正的 stdout/stderr fd 替换 —— main-kts 内部用 println，写的是
 * `System.out`，重定向 PrintStream 就够了。
 */
class DaemonServer(
    private val daemonDir: Path = DaemonPaths.defaultDaemonDir(),
) {

    private val log = LoggerFactory.getLogger("ktx.daemon")
    private val scriptsServed = AtomicLong(0)
    private val startedAt = System.currentTimeMillis()

    // ScriptRunner 在 daemon 启动时构造，常驻进程整个生命周期。
    // 它内部首次 evaluate 时才真正初始化 Kotlin compiler embeddable，
    // 之后所有请求复用 —— 这就是 daemon 削掉「编译器冷启」的关键。
    private val runner = ScriptRunner()

    @Volatile
    private var shutdownRequested = false

    fun serve() {
        daemonDir.createDirectories()
        val socketPath = DaemonPaths.socketFile(daemonDir)
        val pidPath = DaemonPaths.pidFile(daemonDir)

        // 旧 socket 文件可能残留（上次未优雅退出），先清掉
        socketPath.deleteIfExists()
        pidPath.writeText(ProcessHandle.current().pid().toString())

        val address = UnixDomainSocketAddress.of(socketPath)
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
            server.bind(address)
            log.info("daemon listening on {} (pid={})", socketPath, ProcessHandle.current().pid())

            while (!shutdownRequested) {
                val client = try {
                    server.accept()
                } catch (e: Exception) {
                    if (shutdownRequested) break
                    log.warn("accept failed", e)
                    continue
                }
                try {
                    handleClient(client)
                } catch (e: Throwable) {
                    log.error("处理客户端请求失败", e)
                } finally {
                    runCatching { client.close() }
                }
            }
        }

        runCatching { socketPath.deleteIfExists() }
        runCatching { pidPath.deleteIfExists() }
        log.info("daemon exited")
    }

    private fun handleClient(client: SocketChannel) {
        val input = Channels.newInputStream(client)
        val output = Channels.newOutputStream(client)

        val request = Frames.read(input, RequestEnvelope.parser()) ?: return

        if (request.protocolVersion != 0 && request.protocolVersion != PROTOCOL_VERSION.toLong().toInt()) {
            sendError(output, "protocol version mismatch: client=${request.protocolVersion}, daemon=$PROTOCOL_VERSION")
            return
        }

        when (request.bodyCase) {
            RequestEnvelope.BodyCase.RUN -> handleRun(request.run, output)
            RequestEnvelope.BodyCase.SHUTDOWN -> {
                Frames.write(output, ResponseEnvelope.newBuilder().setShutdown(ShutdownResponse.getDefaultInstance()).build())
                shutdownRequested = true
                log.info("收到 shutdown 请求")
            }
            RequestEnvelope.BodyCase.STATUS -> handleStatus(output)
            RequestEnvelope.BodyCase.BODY_NOT_SET -> sendError(output, "request body not set")
            null -> sendError(output, "request body is null")
        }
    }

    /**
     * 跑一个脚本。stdout / stderr 被重定向到「打包成 RunEvent.stdout/stderr 写回 socket」
     * 的 PrintStream；脚本结束后还原。最后发一个 ExitEvent 关闭流。
     *
     * 线程安全：Phase 2.1 串行处理，不需要锁；System.setOut 是进程级全局，
     * 但同时只有一个请求在跑，不会冲突。Phase 2.2 引入并发时这里要改成
     * 「子线程 ThreadLocal stdout 重定向」 —— 暂不预先抽象。
     */
    private fun handleRun(req: RunRequest, output: OutputStream) {
        val origOut = System.out
        val origErr = System.err

        // 包装输出：每次写都立刻 flush 给 socket。脚本里 println 多了不会
        // 攒批量，体感更好。
        val writeLock = Any()
        val stdoutSink = SocketSink(output, isErr = false, writeLock)
        val stderrSink = SocketSink(output, isErr = true, writeLock)
        val newOut = PrintStream(stdoutSink, true, Charsets.UTF_8)
        val newErr = PrintStream(stderrSink, true, Charsets.UTF_8)

        var exitCode = 0
        try {
            System.setOut(newOut)
            System.setErr(newErr)

            val args = req.argsList.toTypedArray()
            val result = when {
                req.scriptPath.isNotEmpty() -> {
                    runner.run(Path.of(req.scriptPath), args)
                }
                req.inlineSource.isNotEmpty() -> {
                    runner.runInline(req.inlineSource, args, virtualName = req.virtualName.ifEmpty { "<inline>" })
                }
                else -> {
                    sendError(output, "RunRequest 必须填 script_path 或 inline_source")
                    return
                }
            }

            // 把诊断输出走 stderr（脚本编译错误、warning）
            result.reports.forEach { report ->
                if (report.severity < ScriptDiagnostic.Severity.INFO) return@forEach
                val target = if (report.severity >= ScriptDiagnostic.Severity.ERROR) newErr else newOut
                target.println(formatReport(report))
            }
            exitCode = if (result is ResultWithDiagnostics.Success) 0 else 1
            scriptsServed.incrementAndGet()
        } catch (t: Throwable) {
            newErr.println("daemon 内部错误：${t.message}")
            log.error("脚本执行异常", t)
            exitCode = 2
        } finally {
            newOut.flush()
            newErr.flush()
            System.setOut(origOut)
            System.setErr(origErr)
        }

        // 最后发 ExitEvent 表示流结束
        synchronized(writeLock) {
            Frames.write(
                output,
                ResponseEnvelope.newBuilder()
                    .setRunEvent(RunEvent.newBuilder().setExit(ExitEvent.newBuilder().setExitCode(exitCode)))
                    .build(),
            )
        }
    }

    private fun handleStatus(output: OutputStream) {
        val rt = Runtime.getRuntime()
        Frames.write(
            output,
            ResponseEnvelope.newBuilder().setStatus(
                StatusResponse.newBuilder()
                    .setUptimeMs(System.currentTimeMillis() - startedAt)
                    .setScriptsServed(scriptsServed.get())
                    .setHeapUsedBytes(rt.totalMemory() - rt.freeMemory())
                    .setHeapMaxBytes(rt.maxMemory())
                    .setKtxVersion(System.getProperty("ktx.version", "dev")),
            ).build(),
        )
    }

    private fun sendError(output: OutputStream, message: String) {
        Frames.write(
            output,
            ResponseEnvelope.newBuilder()
                .setError(io.github.nebula.ktx.proto.v1.ErrorEvent.newBuilder().setMessage(message))
                .build(),
        )
    }

    /**
     * 把字节流打包成 RunEvent.stdout / stderr 写回客户端。flush 时立刻发一帧，
     * 脚本里 println 来一行就走一帧，客户端实时打印。
     */
    private class SocketSink(
        private val output: OutputStream,
        private val isErr: Boolean,
        private val writeLock: Any,
    ) : OutputStream() {
        private val buf = ByteArray(8192)
        private var pos = 0

        override fun write(b: Int) {
            if (pos == buf.size) flush()
            buf[pos++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var remaining = len
            var offset = off
            while (remaining > 0) {
                val space = buf.size - pos
                val toCopy = minOf(space, remaining)
                System.arraycopy(b, offset, buf, pos, toCopy)
                pos += toCopy
                offset += toCopy
                remaining -= toCopy
                if (pos == buf.size) flush()
            }
        }

        override fun flush() {
            if (pos == 0) return
            val payload = ByteString.copyFrom(buf, 0, pos)
            pos = 0
            val envelope = ResponseEnvelope.newBuilder()
                .setRunEvent(
                    RunEvent.newBuilder().apply {
                        if (isErr) setStderr(payload) else setStdout(payload)
                    },
                ).build()
            synchronized(writeLock) { Frames.write(output, envelope) }
        }
    }

    private fun formatReport(r: ScriptDiagnostic): String {
        val location = r.location?.let { " (${it.start.line}:${it.start.col})" }.orEmpty()
        val source = r.sourcePath?.let { " in $it" }.orEmpty()
        return "[${r.severity}]$source$location ${r.message}"
    }
}
