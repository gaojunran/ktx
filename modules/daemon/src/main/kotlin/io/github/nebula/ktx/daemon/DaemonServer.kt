package io.github.nebula.ktx.daemon

import com.google.protobuf.ByteString
import io.github.nebula.ktx.core.deps.FrozenResolver
import io.github.nebula.ktx.core.deps.Lockfile
import io.github.nebula.ktx.core.exec.ScriptRunner
import io.github.nebula.ktx.proto.v1.ExitEvent
import io.github.nebula.ktx.proto.v1.Frames
import io.github.nebula.ktx.proto.v1.PROTOCOL_VERSION
import io.github.nebula.ktx.proto.v1.RequestEnvelope
import io.github.nebula.ktx.proto.v1.ResolverMode
import io.github.nebula.ktx.proto.v1.ResponseEnvelope
import io.github.nebula.ktx.proto.v1.RunEvent
import io.github.nebula.ktx.proto.v1.RunRequest
import io.github.nebula.ktx.proto.v1.ShutdownResponse
import io.github.nebula.ktx.proto.v1.StatusResponse
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.io.PrintStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * ktx daemon 服务端。
 *
 * Phase 2.2 起：
 *   - 并发处理（cachedThreadPool，每客户端一个 worker）
 *   - stdout/stderr 通过 [RoutedOutputStream] 按 worker 线程隔离
 *
 * 仍然不做：toolchain 路由（CLI 端按 key 启不同 daemon 实例）、生命周期
 * 子任务在另一个 commit 里加。
 */
class DaemonServer(
    private val daemonDir: Path = DaemonPaths.defaultDaemonDir(),
) {

    private val log = LoggerFactory.getLogger("ktx.daemon")
    private val scriptsServed = AtomicLong(0)
    private val startedAt = System.currentTimeMillis()
    private val lastActivityMs = AtomicLong(System.currentTimeMillis())
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "ktx-daemon-worker-${nextWorkerId.getAndIncrement()}").apply {
            isDaemon = true
        }
    }

    /** 监督线程池，跑 idle / heap 检查。daemon 整个生命周期一份。 */
    private val supervisor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ktx-daemon-supervisor").apply { isDaemon = true }
    }

    private val runner = ScriptRunner()

    @Volatile
    private var shutdownRequested = false

    fun serve() {
        daemonDir.createDirectories()
        val socketPath = DaemonPaths.socketFile(daemonDir)
        val pidPath = DaemonPaths.pidFile(daemonDir)
        val keyPath = DaemonPaths.keyFile(daemonDir)

        socketPath.deleteIfExists()
        pidPath.writeText(ProcessHandle.current().pid().toString())
        // 把当前 JVM 主版本写到 key.txt，方便排错（验证路由进对了 daemon）
        keyPath.writeText("jdk=${Runtime.version().feature()};protocol=v1\n")

        // 一次性把 System.out/err 替换成 routed 版本：worker 线程 bind 自己
        // 的 sink 时立刻生效；没 bind 的（daemon 主线程、logback 后台线程）
        // 走 fallback —— 我们指向 /dev/null 等价物，因为 daemon 主线程
        // println 到原 stdout 没人接收。
        val routedOut = RoutedOutputStream(OutputStream.nullOutputStream())
        val routedErr = RoutedOutputStream(OutputStream.nullOutputStream())
        System.setOut(PrintStream(routedOut, true, Charsets.UTF_8))
        System.setErr(PrintStream(routedErr, true, Charsets.UTF_8))

        val address = UnixDomainSocketAddress.of(socketPath)
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
            server.bind(address)
            log.info("daemon listening on {} (pid={}, idle-timeout={}min)", socketPath, ProcessHandle.current().pid(), idleTimeoutMin)

            startSupervisor(server)

            while (!shutdownRequested) {
                val client = try {
                    server.accept()
                } catch (e: Exception) {
                    if (shutdownRequested) break
                    log.warn("accept failed", e)
                    continue
                }
                lastActivityMs.set(System.currentTimeMillis())
                executor.submit {
                    try {
                        handleClient(client, routedOut, routedErr)
                    } catch (e: Throwable) {
                        log.error("处理客户端请求失败", e)
                    } finally {
                        runCatching { client.close() }
                        lastActivityMs.set(System.currentTimeMillis())
                    }
                }
            }
        }

        supervisor.shutdownNow()
        executor.shutdown()
        runCatching { socketPath.deleteIfExists() }
        runCatching { pidPath.deleteIfExists() }
        log.info("daemon exited")
    }

    /**
     * 启动后台监督任务：
     *   - 每 30 秒检查空闲时长，超过 [idleTimeoutMin] 就请求关闭
     *   - 每 60 秒检查 heap 占用，超过 [heapWarnPct]% 时主动 GC 一次并打 warning
     *
     * 用 ServerSocketChannel.close() 唤醒 accept 循环退出。
     */
    private fun startSupervisor(server: ServerSocketChannel) {
        supervisor.scheduleAtFixedRate({
            try {
                val idleMs = System.currentTimeMillis() - lastActivityMs.get()
                if (idleMs > TimeUnit.MINUTES.toMillis(idleTimeoutMin.toLong())) {
                    log.info("空闲 {} 分钟，自杀", idleTimeoutMin)
                    shutdownRequested = true
                    runCatching { server.close() }
                }
            } catch (t: Throwable) {
                log.warn("idle 检查异常", t)
            }
        }, 30, 30, TimeUnit.SECONDS)

        supervisor.scheduleAtFixedRate({
            try {
                val rt = Runtime.getRuntime()
                val used = rt.totalMemory() - rt.freeMemory()
                val max = rt.maxMemory()
                val pct = (used.toDouble() / max * 100).toInt()
                if (pct >= heapWarnPct) {
                    log.warn("heap 占用 {}% ({}M/{}M)，主动 System.gc()", pct, used / 1024 / 1024, max / 1024 / 1024)
                    System.gc()
                }
            } catch (t: Throwable) {
                log.warn("heap 检查异常", t)
            }
        }, 60, 60, TimeUnit.SECONDS)
    }

    private fun handleClient(
        client: SocketChannel,
        routedOut: RoutedOutputStream,
        routedErr: RoutedOutputStream,
    ) {
        val input = Channels.newInputStream(client)
        val output = Channels.newOutputStream(client)

        val request = Frames.read(input, RequestEnvelope.parser()) ?: return

        if (request.protocolVersion != 0 && request.protocolVersion != PROTOCOL_VERSION) {
            sendError(output, "protocol version mismatch: client=${request.protocolVersion}, daemon=$PROTOCOL_VERSION")
            return
        }

        when (request.bodyCase) {
            RequestEnvelope.BodyCase.RUN -> handleRun(request.run, output, routedOut, routedErr)
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
     * 跑一个脚本。worker 线程把 routed stdout/err bind 到「写回这个 client
     * 的 sink」，脚本里 println 自动落到对的 socket。
     *
     * stdin：客户端在 RunRequest 里一次性塞了完整字节，daemon 把它包装成
     * ByteArrayInputStream 临时替换 `System.in`。同样用 ThreadLocal 路由
     * 避免并发冲突 —— 但实现简化为「请求级 setIn / 完成后还原」（仅在
     * stdin_bytes 非空时介入），并发场景下偶尔互相覆盖也只是 stdin 看到
     * 错误的一份字节，与 println 错位相比影响小得多。后续如果用得多再改
     * 成 routed System.in。
     */
    private fun handleRun(
        req: RunRequest,
        output: OutputStream,
        routedOut: RoutedOutputStream,
        routedErr: RoutedOutputStream,
    ) {
        val writeLock = Any()
        val stdoutSink = SocketSink(output, isErr = false, writeLock)
        val stderrSink = SocketSink(output, isErr = true, writeLock)

        var exitCode = 0
        val origIn = System.`in`
        val stdinOverride = req.stdin?.takeIf { !it.isEmpty }?.let {
            java.io.ByteArrayInputStream(it.toByteArray())
        }
        try {
            routedOut.bind(stdoutSink)
            routedErr.bind(stderrSink)
            if (stdinOverride != null) {
                System.setIn(stdinOverride)
            }

            val args = req.argsList.toTypedArray()
            val resolver = when (req.resolverMode) {
                ResolverMode.FROZEN -> {
                    val lockPath = req.lockfilePath.takeIf { it.isNotEmpty() }
                        ?: error("FROZEN 模式必须指定 lockfile_path")
                    val lockfile = Lockfile.read(Path.of(lockPath))
                        ?: error("lockfile 不存在：$lockPath")
                    FrozenResolver(lockfile)
                }
                ResolverMode.NORMAL, ResolverMode.UNRECOGNIZED, null -> null
            }

            val result = when {
                req.scriptPath.isNotEmpty() -> {
                    runner.run(Path.of(req.scriptPath), args, resolver = resolver)
                }
                req.inlineSource.isNotEmpty() -> {
                    runner.runInline(
                        req.inlineSource,
                        args,
                        virtualName = req.virtualName.ifEmpty { "<inline>" },
                        resolver = resolver,
                    )
                }
                else -> {
                    sendError(output, "RunRequest 必须填 script_path 或 inline_source")
                    return
                }
            }

            result.reports.forEach { report ->
                if (report.severity < ScriptDiagnostic.Severity.INFO) return@forEach
                val target = if (report.severity >= ScriptDiagnostic.Severity.ERROR) System.err else System.out
                target.println(formatReport(report))
            }
            exitCode = if (result is ResultWithDiagnostics.Success) 0 else 1
            scriptsServed.incrementAndGet()
        } catch (t: Throwable) {
            System.err.println("daemon 内部错误：${t.message}")
            log.error("脚本执行异常", t)
            exitCode = 2
        } finally {
            stdoutSink.flush()
            stderrSink.flush()
            routedOut.unbind()
            routedErr.unbind()
            if (stdinOverride != null) System.setIn(origIn)
        }

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

    companion object {
        private val nextWorkerId = AtomicLong(1)

        /** 默认 30 分钟，可通过 KTX_DAEMON_IDLE_TIMEOUT_MIN 覆盖。 */
        private val idleTimeoutMin: Int =
            System.getenv("KTX_DAEMON_IDLE_TIMEOUT_MIN")?.toIntOrNull() ?: 30

        /** 默认 80%，可通过 KTX_DAEMON_HEAP_WARN_PCT 覆盖。 */
        private val heapWarnPct: Int =
            System.getenv("KTX_DAEMON_HEAP_WARN_PCT")?.toIntOrNull() ?: 80
    }
}
