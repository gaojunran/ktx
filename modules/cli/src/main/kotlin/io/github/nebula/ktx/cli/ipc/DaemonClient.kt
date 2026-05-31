package io.github.nebula.ktx.cli.ipc

import io.github.nebula.ktx.daemon.DaemonPaths
import io.github.nebula.ktx.proto.v1.Frames
import io.github.nebula.ktx.proto.v1.PROTOCOL_VERSION
import io.github.nebula.ktx.proto.v1.RequestEnvelope
import io.github.nebula.ktx.proto.v1.ResolverMode
import io.github.nebula.ktx.proto.v1.ResponseEnvelope
import io.github.nebula.ktx.proto.v1.RunRequest
import io.github.nebula.ktx.proto.v1.ShutdownRequest
import io.github.nebula.ktx.proto.v1.StatusRequest
import io.github.nebula.ktx.proto.v1.StdinChunk
import org.slf4j.LoggerFactory
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Path

/**
 * CLI-side client that connects to the daemon, sends requests, and reads
 * streaming responses.
 *
 * Phase 2.1 contract: each [run] / [status] / [shutdown] call opens a fresh
 * connection, sends one request, and reads a single response (or a stream
 * for run), then closes. Simple and thread-safe.
 *
 * Phase 2.2 may introduce a connection pool or multiplexing, but as long as
 * the daemon serializes requests per-client there is no benefit to opening
 * multiple connections.
 */
class DaemonClient(
    private val socketPath: Path = DaemonPaths.socketFile(),
) {
    private val log = LoggerFactory.getLogger("ktx.cli.daemon-client")

    /**
     * Run a script in the daemon, streaming stdout / stderr to the local
     * console, and return the exit code.
     *
     * Since Phase 2.3: [stdin] is a streaming [InputStream] rather than a
     * single ByteArray. The client spawns a background thread that reads
     * bytes from stdin and packages them into StdinChunk frames sent to the
     * daemon, until EOF. The daemon-side script can read incrementally.
     *
     * On failure (connection error, protocol mismatch, ...) throws IOException;
     * the caller decides whether to fall back.
     */
    fun run(
        scriptPath: Path?,
        inlineSource: String?,
        scriptArgs: List<String>,
        virtualName: String = "<inline>",
        resolverMode: ResolverMode = ResolverMode.NORMAL,
        lockfilePath: Path? = null,
        stdin: java.io.InputStream? = null,
    ): Int {
        require((scriptPath == null) xor (inlineSource == null)) { "exactly one of scriptPath / inlineSource is required" }

        connect().use { ch ->
            val out = Channels.newOutputStream(ch)
            val input = Channels.newInputStream(ch)

            val req = RunRequest.newBuilder()
                .addAllArgs(scriptArgs)
                .setVirtualName(virtualName)
                .setCwd(System.getProperty("user.dir"))
                .setResolverMode(resolverMode)
                .also {
                    if (scriptPath != null) it.scriptPath = scriptPath.toAbsolutePath().toString()
                    if (inlineSource != null) it.inlineSource = inlineSource
                    if (lockfilePath != null) it.lockfilePath = lockfilePath.toAbsolutePath().toString()
                }
                .build()
            // RUN frame
            Frames.write(
                out,
                RequestEnvelope.newBuilder()
                    .setProtocolVersion(PROTOCOL_VERSION)
                    .setRun(req)
                    .build(),
            )

            // Start the stdin pump thread: read bytes from the user's stdin
            // and ship them as StdinChunk frames. With no stdin source (null)
            // we skip the thread; the daemon then sees an empty pipe and any
            // readLine() in the script returns null immediately.
            //
            // Writing frames must coexist with the reply thread (the current
            // thread reading RunEvents) on the same socket output, guarded
            // by a mutex. Frames.write writes a complete frame before
            // returning, so a single lock is enough.
            val outLock = Any()
            val pumpThread = if (stdin != null) startStdinPump(stdin, out, outLock) else null

            try {
                while (true) {
                    val resp = Frames.read(input, ResponseEnvelope.parser())
                        ?: error("daemon closed connection prematurely (script may have been interrupted by a daemon crash)")
                    when (resp.bodyCase) {
                        ResponseEnvelope.BodyCase.RUN_EVENT -> {
                            val ev = resp.runEvent
                            when (ev.kindCase) {
                                io.github.nebula.ktx.proto.v1.RunEvent.KindCase.STDOUT ->
                                    System.out.write(ev.stdout.toByteArray()).also { System.out.flush() }
                                io.github.nebula.ktx.proto.v1.RunEvent.KindCase.STDERR ->
                                    System.err.write(ev.stderr.toByteArray()).also { System.err.flush() }
                                io.github.nebula.ktx.proto.v1.RunEvent.KindCase.EXIT ->
                                    return ev.exit.exitCode
                                io.github.nebula.ktx.proto.v1.RunEvent.KindCase.KIND_NOT_SET, null ->
                                    error("RunEvent kind not set")
                            }
                        }
                        ResponseEnvelope.BodyCase.ERROR ->
                            error("daemon error: ${resp.error.message}")
                        else -> error("unexpected response type: ${resp.bodyCase}")
                    }
                }
            } finally {
                pumpThread?.interrupt()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    /**
     * Continuously read bytes from [stdin] and ship them through [out] as
     * StdinChunk frames in 4 KB segments. On EOF, send a final frame with
     * eof=true. Socket-closure exceptions are swallowed silently (the main
     * thread will detect them first).
     */
    private fun startStdinPump(
        stdin: java.io.InputStream,
        out: java.io.OutputStream,
        outLock: Any,
    ): Thread = Thread({
        val buf = ByteArray(4096)
        try {
            while (!Thread.currentThread().isInterrupted) {
                val n = stdin.read(buf)
                if (n < 0) break
                if (n == 0) continue
                val chunk = StdinChunk.newBuilder()
                    .setData(com.google.protobuf.ByteString.copyFrom(buf, 0, n))
                    .build()
                synchronized(outLock) {
                    Frames.write(
                        out,
                        RequestEnvelope.newBuilder()
                            .setProtocolVersion(PROTOCOL_VERSION)
                            .setStdinChunk(chunk)
                            .build(),
                    )
                }
            }
            if (!Thread.currentThread().isInterrupted) {
                synchronized(outLock) {
                    Frames.write(
                        out,
                        RequestEnvelope.newBuilder()
                            .setProtocolVersion(PROTOCOL_VERSION)
                            .setStdinChunk(StdinChunk.newBuilder().setEof(true).build())
                            .build(),
                    )
                }
            }
        } catch (_: Throwable) {
            // Swallow any exception silently: the common case is that the
            // script never reads stdin and the daemon has already sent
            // ExitEvent and closed the connection; the pump trying to write
            // to the socket then sees channel-closed errors as a normal exit
            // path. The main thread is responsible for receiving ExitEvent
            // and does not depend on the pump for error reporting.
        }
    }, "ktx-stdin-pump").apply {
        isDaemon = true
        start()
    }

    fun status(): io.github.nebula.ktx.proto.v1.StatusResponse {
        connect().use { ch ->
            val out = Channels.newOutputStream(ch)
            val input = Channels.newInputStream(ch)
            Frames.write(
                out,
                RequestEnvelope.newBuilder()
                    .setProtocolVersion(PROTOCOL_VERSION)
                    .setStatus(StatusRequest.getDefaultInstance())
                    .build(),
            )
            val resp = Frames.read(input, ResponseEnvelope.parser())
                ?: error("daemon closed connection prematurely")
            return resp.status
        }
    }

    fun shutdown() {
        connect().use { ch ->
            val out = Channels.newOutputStream(ch)
            val input = Channels.newInputStream(ch)
            Frames.write(
                out,
                RequestEnvelope.newBuilder()
                    .setProtocolVersion(PROTOCOL_VERSION)
                    .setShutdown(ShutdownRequest.getDefaultInstance())
                    .build(),
            )
            // Read ShutdownResponse as acknowledgement; ignore content.
            Frames.read(input, ResponseEnvelope.parser())
        }
    }

    private fun connect(): SocketChannel {
        log.debug("connecting to {}", socketPath)
        val address = UnixDomainSocketAddress.of(socketPath)
        return SocketChannel.open(StandardProtocolFamily.UNIX).apply {
            connect(address)
        }
    }
}
