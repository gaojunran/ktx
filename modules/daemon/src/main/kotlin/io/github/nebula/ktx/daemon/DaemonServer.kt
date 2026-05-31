package io.github.nebula.ktx.daemon

import com.google.protobuf.ByteString
import io.github.nebula.ktx.core.deps.FrozenResolver
import io.github.nebula.ktx.core.deps.Lockfile
import io.github.nebula.ktx.core.deps.LockingFlow
import io.github.nebula.ktx.core.deps.RecordingResolver
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
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
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
 * Phase 2.3 起：
 *   - 流式 stdin（StdinChunk 帧 → PipedOutputStream → 脚本 System.in）
 *   - LOCK resolver 模式（在 daemon 内跑 lock 流程，写 lockfile）
 *   - System.in 也走 [RoutedInputStream] 按 worker 线程隔离
 *
 * Phase 2.2 已有：
 *   - 并发处理（cachedThreadPool，每客户端一个 worker）
 *   - stdout/stderr 通过 [RoutedOutputStream] 按 worker 线程隔离
 *   - toolchain 路由（CLI 端按 jdkMajor 启不同 daemon 实例）
 *   - 空闲超时 + heap watchdog
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
        keyPath.writeText("jdk=${Runtime.version().feature()};protocol=v1\n")

        val routedOut = RoutedOutputStream(OutputStream.nullOutputStream())
        val routedErr = RoutedOutputStream(OutputStream.nullOutputStream())
        val routedIn = RoutedInputStream()
        System.setOut(PrintStream(routedOut, true, Charsets.UTF_8))
        System.setErr(PrintStream(routedErr, true, Charsets.UTF_8))
        System.setIn(routedIn)

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
                        handleClient(client, routedOut, routedErr, routedIn)
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
        routedIn: RoutedInputStream,
    ) {
        val input = Channels.newInputStream(client)
        val output = Channels.newOutputStream(client)

        val request = Frames.read(input, RequestEnvelope.parser()) ?: return

        if (request.protocolVersion != 0 && request.protocolVersion != PROTOCOL_VERSION) {
            sendError(output, "protocol version mismatch: client=${request.protocolVersion}, daemon=$PROTOCOL_VERSION")
            return
        }

        when (request.bodyCase) {
            RequestEnvelope.BodyCase.RUN -> handleRun(request.run, input, output, routedOut, routedErr, routedIn)
            RequestEnvelope.BodyCase.SHUTDOWN -> {
                Frames.write(output, ResponseEnvelope.newBuilder().setShutdown(ShutdownResponse.getDefaultInstance()).build())
                shutdownRequested = true
                log.info("收到 shutdown 请求")
            }
            RequestEnvelope.BodyCase.STATUS -> handleStatus(output)
            RequestEnvelope.BodyCase.STDIN_CHUNK ->
                sendError(output, "首帧不能是 StdinChunk —— 必须先发 RunRequest")
            RequestEnvelope.BodyCase.BODY_NOT_SET -> sendError(output, "request body not set")
            null -> sendError(output, "request body is null")
        }
    }

    /**
     * 处理 RUN 请求。
     *
     * 架构：
     *   - 当前线程（worker A）跑脚本：bind routed in/out/err 后调 runner.run。
     *   - **如果**客户端会发 stdin chunks：本来需要后台线程从 socket 读 chunks
     *     写 pipe。但脚本 run 是阻塞的，本线程没法同时干两件事。
     *     解法：另起一个线程当「stdin pump」，从 socket input 读后续帧、把
     *     data 写到 PipedOutputStream，eof 时关 pipe。
     *
     * 简化：
     *   - 老 `req.stdin` bytes 字段（Phase 2.2 兼容）：先把它写进 pipe，再
     *     开 pump 线程读 socket。
     *   - 没有 stdin 来源（也没 chunks）：直接给脚本一个 nullInputStream。
     *
     * LOCK 模式：用 RecordingResolver + bypassCompiledCache，跑完后写 lockfile。
     */
    private fun handleRun(
        req: RunRequest,
        input: InputStream,
        output: OutputStream,
        routedOut: RoutedOutputStream,
        routedErr: RoutedOutputStream,
        routedIn: RoutedInputStream,
    ) {
        val writeLock = Any()
        val stdoutSink = SocketSink(output, isErr = false, writeLock)
        val stderrSink = SocketSink(output, isErr = true, writeLock)

        // stdin 链路：用 PipedOutputStream/InputStream 桥。pump 线程读 socket
        // chunks 写 pipe；脚本读 pipe 端。如果客户端不会发 chunks（老协议
        // bytes 字段或根本没 stdin），直接拿固定 stream 也行 —— 但为了简化
        // 一律走 pipe。
        val stdinPipeReader = PipedInputStream(64 * 1024)
        val stdinPipeWriter = PipedOutputStream(stdinPipeReader)

        // 老 bytes 字段兼容
        if (req.stdin != null && !req.stdin.isEmpty) {
            stdinPipeWriter.write(req.stdin.toByteArray())
        }

        val pumpThread = Thread({
            stdinPump(input, stdinPipeWriter)
        }, "ktx-daemon-stdin-pump-${nextWorkerId.get()}").apply {
            isDaemon = true
            start()
        }

        var exitCode = 0
        try {
            routedOut.bind(stdoutSink)
            routedErr.bind(stderrSink)
            routedIn.bind(stdinPipeReader)

            val args = req.argsList.toTypedArray()
            val result = when (req.resolverMode) {
                ResolverMode.LOCK -> runLockMode(req, args)
                else -> runNormalOrFrozen(req, args)
            }

            if (result == null) {
                sendError(output, "RunRequest 必须填 script_path 或 inline_source")
                return
            }

            result.reports.forEach { report ->
                if (report.severity < ScriptDiagnostic.Severity.INFO) return@forEach
                val target = if (report.severity >= ScriptDiagnostic.Severity.ERROR) System.err else System.out
                target.println(formatReport(report))
            }
            exitCode = if (result is ResultWithDiagnostics.Success) 0 else 1
            scriptsServed.incrementAndGet()
        } catch (t: Throwable) {
            // 用 routed System.err 让错误流回客户端 stderr，方便用户诊断
            try {
                System.err.println("daemon 内部错误：${t.message}")
                t.printStackTrace(System.err)
            } catch (_: Throwable) { /* 流可能已坏 */ }
            log.error("脚本执行异常", t)
            exitCode = 2
        } finally {
            // 顺序：先 flush sinks（仍 bind 着，写帧能正确序列化），再 unbind，
            // 再发 ExitEvent，最后清 stdin pipe / pump。
            runCatching { stdoutSink.flush() }
            runCatching { stderrSink.flush() }
            routedOut.unbind()
            routedErr.unbind()
            routedIn.unbind()
        }

        // 发 ExitEvent 必须在 finally 之外、unbind 之后 —— 否则它如果走到
        // 已 unbind 的 routedOut（不是这里的 socket sink）会写到 fallback。
        // 注意：这里出现异常就让 client 看到 EOF 自行处理，不再尝试发帧。
        try {
            synchronized(writeLock) {
                Frames.write(
                    output,
                    ResponseEnvelope.newBuilder()
                        .setRunEvent(RunEvent.newBuilder().setExit(ExitEvent.newBuilder().setExitCode(exitCode)))
                        .build(),
                )
            }
        } catch (t: Throwable) {
            log.warn("发送 ExitEvent 失败：{}", t.message)
        } finally {
            runCatching { stdinPipeWriter.close() }
            runCatching { stdinPipeReader.close() }
            runCatching { pumpThread.interrupt() }
        }
    }

    /**
     * 后台 stdin pump：从 socket 持续读 RequestEnvelope，遇到 StdinChunk
     * 就把 data 写到 [stdinPipeWriter]。eof=true 或读到 EOF / 异常时退出。
     */
    private fun stdinPump(input: InputStream, stdinPipeWriter: PipedOutputStream) {
        try {
            while (true) {
                val req = Frames.read(input, RequestEnvelope.parser()) ?: break
                if (req.bodyCase != RequestEnvelope.BodyCase.STDIN_CHUNK) {
                    log.warn("stdin pump 收到非 StdinChunk 帧：{}", req.bodyCase)
                    continue
                }
                val chunk = req.stdinChunk
                if (chunk.data != null && !chunk.data.isEmpty) {
                    stdinPipeWriter.write(chunk.data.toByteArray())
                    stdinPipeWriter.flush()
                }
                if (chunk.eof) break
            }
        } catch (_: java.io.IOException) {
            // socket 关或 pipe 关：正常路径
        } catch (t: Throwable) {
            log.warn("stdin pump 异常", t)
        } finally {
            runCatching { stdinPipeWriter.close() }
        }
    }

    private fun runNormalOrFrozen(req: RunRequest, args: Array<String>): ResultWithDiagnostics<*>? {
        val resolver = when (req.resolverMode) {
            ResolverMode.FROZEN -> {
                val lockPath = req.lockfilePath.takeIf { it.isNotEmpty() }
                    ?: error("FROZEN 模式必须指定 lockfile_path")
                val lockfile = Lockfile.read(Path.of(lockPath))
                    ?: error("lockfile 不存在：$lockPath")
                FrozenResolver(lockfile)
            }
            else -> null
        }
        return when {
            req.scriptPath.isNotEmpty() -> runner.run(Path.of(req.scriptPath), args, resolver = resolver)
            req.inlineSource.isNotEmpty() -> runner.runInline(
                req.inlineSource,
                args,
                virtualName = req.virtualName.ifEmpty { "<inline>" },
                resolver = resolver,
            )
            else -> null
        }
    }

    /**
     * LOCK 模式：仅支持 file 模式（inline 没有"对应的脚本路径"概念）。
     *
     * 与 `ktx lock` 子命令语义一致：仅触发依赖解析、写 lockfile，**不**跑
     * 脚本主体（避免 HTTP 请求等副作用）。这与 `ktx run --lock` 在非 daemon
     * 路径下的"跑+刷"语义略不同 —— daemon 共享编译缓存，要禁用缓存才能让
     * RecordingResolver 拿到记录，禁用缓存又会让"跑"耗时，权衡后 daemon
     * LOCK 走纯 lock 流程。
     *
     * 进程级状态污染：LockingFlow 内部改 system property 临时禁用缓存，跑完
     * 恢复。daemon 并发期间另一个 worker 可能恰好在编译，会把它推到无缓存
     * 路径。用 [lockSerializer] 串行化所有 LOCK 请求规避（lock 极少见、用户
     * 可接受短暂排队）。
     */
    private fun runLockMode(req: RunRequest, args: Array<String>): ResultWithDiagnostics<*>? {
        val scriptPath = req.scriptPath.takeIf { it.isNotEmpty() }
            ?: error("LOCK 模式仅支持 script_path（inline 无对应 lockfile）")
        return synchronized(lockSerializer) {
            LockingFlow.lockAndOptionallyRun(
                runner = runner,
                scriptPath = Path.of(scriptPath),
                scriptArgs = args,
                skipExecution = true,
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
        private val lockSerializer = Any()

        private val idleTimeoutMin: Int =
            System.getenv("KTX_DAEMON_IDLE_TIMEOUT_MIN")?.toIntOrNull() ?: 30

        private val heapWarnPct: Int =
            System.getenv("KTX_DAEMON_HEAP_WARN_PCT")?.toIntOrNull() ?: 80
    }
}
