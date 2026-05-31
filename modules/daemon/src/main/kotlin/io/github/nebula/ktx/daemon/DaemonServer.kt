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
 * The ktx daemon server.
 *
 * Since Phase 2.3:
 *   - streaming stdin (StdinChunk frames -> PipedOutputStream -> script System.in)
 *   - LOCK resolver mode (run the lock flow inside the daemon, write lockfile)
 *   - System.in is also routed per worker thread via [RoutedInputStream]
 *
 * Already in Phase 2.2:
 *   - concurrent handling (cachedThreadPool, one worker per client)
 *   - stdout/stderr routed per worker thread via [RoutedOutputStream]
 *   - toolchain routing (CLI starts a different daemon instance per jdkMajor)
 *   - idle timeout + heap watchdog
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
                        log.error("client request failed", e)
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
                    log.info("idle for {} minutes, exiting", idleTimeoutMin)
                    shutdownRequested = true
                    runCatching { server.close() }
                }
            } catch (t: Throwable) {
                log.warn("idle check failed", t)
            }
        }, 30, 30, TimeUnit.SECONDS)

        supervisor.scheduleAtFixedRate({
            try {
                val rt = Runtime.getRuntime()
                val used = rt.totalMemory() - rt.freeMemory()
                val max = rt.maxMemory()
                val pct = (used.toDouble() / max * 100).toInt()
                if (pct >= heapWarnPct) {
                    log.warn("heap usage {}% ({}M/{}M), forcing System.gc()", pct, used / 1024 / 1024, max / 1024 / 1024)
                    System.gc()
                }
            } catch (t: Throwable) {
                log.warn("heap check failed", t)
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
                log.info("shutdown request received")
            }
            RequestEnvelope.BodyCase.STATUS -> handleStatus(output)
            RequestEnvelope.BodyCase.STDIN_CHUNK ->
                sendError(output, "first frame cannot be StdinChunk; RunRequest must come first")
            RequestEnvelope.BodyCase.BODY_NOT_SET -> sendError(output, "request body not set")
            null -> sendError(output, "request body is null")
        }
    }

    /**
     * Handle a RUN request.
     *
     * Architecture:
     *   - The current thread (worker A) runs the script: bind routed
     *     in/out/err and call runner.run.
     *   - **If** the client will send stdin chunks: ideally a background
     *     thread reads chunks from the socket and writes them to the pipe.
     *     But runner.run is blocking, so this thread cannot do both.
     *     Solution: spawn a separate "stdin pump" thread that reads
     *     subsequent frames from the socket input, writes data to the
     *     PipedOutputStream, and closes the pipe on EOF.
     *
     * Simplifications:
     *   - Legacy `req.stdin` bytes field (Phase 2.2 compat): write it
     *     into the pipe before starting the pump thread.
     *   - No stdin source at all (no chunks): pass nullInputStream to
     *     the script.
     *
     * LOCK mode: use RecordingResolver + bypassCompiledCache, then write
     * the lockfile after running.
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

        // stdin path: bridge via PipedOutputStream/InputStream. The pump
        // thread reads StdinChunk frames from the socket and writes to the
        // pipe; the script reads from the pipe end. When the client will
        // never send chunks (legacy bytes field, or no stdin at all), a
        // fixed stream would also work, but always going through the pipe
        // keeps things simple.
        val stdinPipeReader = PipedInputStream(64 * 1024)
        val stdinPipeWriter = PipedOutputStream(stdinPipeReader)

        // Legacy bytes-field compatibility
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
                sendError(output, "RunRequest must specify either script_path or inline_source")
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
            // Use the routed System.err so the error reaches the client's
            // stderr and aids diagnosis.
            try {
                System.err.println("daemon internal error: ${t.message}")
                t.printStackTrace(System.err)
            } catch (_: Throwable) { /* the stream may be broken */ }
            log.error("script execution failed", t)
            exitCode = 2
        } finally {
            // Order: flush sinks first (still bound, frames can serialize
            // correctly), then unbind, then send ExitEvent, finally close
            // the stdin pipe / pump.
            runCatching { stdoutSink.flush() }
            runCatching { stderrSink.flush() }
            routedOut.unbind()
            routedErr.unbind()
            routedIn.unbind()
        }

        // ExitEvent must be sent outside the finally block, after unbind;
        // otherwise it might be routed to the (now-unbound) routedOut and
        // hit the fallback sink instead of this socket.
        // Note: if this throws, we let the client see EOF and handle it
        // itself; we don't try to send another frame.
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
            log.warn("failed to send ExitEvent: {}", t.message)
        } finally {
            runCatching { stdinPipeWriter.close() }
            runCatching { stdinPipeReader.close() }
            runCatching { pumpThread.interrupt() }
        }
    }

    /**
     * Background stdin pump: continuously reads RequestEnvelope from the
     * socket; on each StdinChunk, writes data to [stdinPipeWriter]. Exits
     * on eof=true, on EOF, or on any exception.
     */
    private fun stdinPump(input: InputStream, stdinPipeWriter: PipedOutputStream) {
        try {
            while (true) {
                val req = Frames.read(input, RequestEnvelope.parser()) ?: break
                if (req.bodyCase != RequestEnvelope.BodyCase.STDIN_CHUNK) {
                    log.warn("stdin pump received non-StdinChunk frame: {}", req.bodyCase)
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
            // socket closed or pipe closed: normal exit path
        } catch (t: Throwable) {
            log.warn("stdin pump failed", t)
        } finally {
            runCatching { stdinPipeWriter.close() }
        }
    }

    private fun runNormalOrFrozen(req: RunRequest, args: Array<String>): ResultWithDiagnostics<*>? {
        val resolver = when (req.resolverMode) {
            ResolverMode.FROZEN -> {
                val lockPath = req.lockfilePath.takeIf { it.isNotEmpty() }
                    ?: error("FROZEN mode requires lockfile_path")
                val lockfile = Lockfile.read(Path.of(lockPath))
                    ?: error("lockfile not found: $lockPath")
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
     * LOCK mode: file mode only (inline source has no "corresponding script
     * path" notion).
     *
     * Same semantics as the `ktx lock` subcommand: only trigger dependency
     * resolution and write the lockfile; do **not** execute the script body
     * (avoids side effects like HTTP calls). This differs slightly from the
     * "run + refresh" semantics of `ktx run --lock` on the non-daemon path:
     * the daemon shares the compile cache, and disabling that cache is
     * required to give RecordingResolver records to capture; running with
     * the cache disabled is slow, so the daemon LOCK path is pure-lock as a
     * tradeoff.
     *
     * Process-wide state pollution: LockingFlow temporarily mutates a system
     * property to disable the cache, restoring it after running. While this
     * is in flight another concurrent worker may be compiling and would be
     * forced onto the no-cache path. We avoid that by serializing all LOCK
     * requests via [lockSerializer] (lock is rare; brief queueing is
     * acceptable).
     */
    private fun runLockMode(req: RunRequest, args: Array<String>): ResultWithDiagnostics<*>? {
        val scriptPath = req.scriptPath.takeIf { it.isNotEmpty() }
            ?: error("LOCK mode supports script_path only (no lockfile for inline source)")
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
