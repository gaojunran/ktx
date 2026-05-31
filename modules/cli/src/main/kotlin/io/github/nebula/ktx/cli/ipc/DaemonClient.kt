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
import org.slf4j.LoggerFactory
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Path

/**
 * CLI 端连接 daemon、发请求、读取流式响应。
 *
 * Phase 2.1 接口：每个 [run] / [status] / [shutdown] 调用各开一个新连接，
 * 一发一收（或一发多收 for run），结束就关。简单、线程安全。
 *
 * 后续 Phase 2.2 引入连接池或多路复用时再优化 —— 当前 daemon 单 client
 * 串行处理，开多连接也没意义。
 */
class DaemonClient(
    private val socketPath: Path = DaemonPaths.socketFile(),
) {
    private val log = LoggerFactory.getLogger("ktx.cli.daemon-client")

    /**
     * 在 daemon 中跑一个脚本，把 stdout / stderr 流式打印到本地，返回 exit code。
     *
     * 失败（连接异常、协议不匹配等）抛 IOException，调用方自行决定是否回退。
     */
    fun run(
        scriptPath: Path?,
        inlineSource: String?,
        scriptArgs: List<String>,
        virtualName: String = "<inline>",
        resolverMode: ResolverMode = ResolverMode.NORMAL,
        lockfilePath: Path? = null,
        stdin: ByteArray? = null,
    ): Int {
        require((scriptPath == null) xor (inlineSource == null)) { "scriptPath / inlineSource 二选一" }

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
                    if (stdin != null) it.stdin = com.google.protobuf.ByteString.copyFrom(stdin)
                }
                .build()
            Frames.write(
                out,
                RequestEnvelope.newBuilder()
                    .setProtocolVersion(PROTOCOL_VERSION)
                    .setRun(req)
                    .build(),
            )

            // 读流：stdout / stderr 实时打印，遇到 ExitEvent 收尾
            while (true) {
                val resp = Frames.read(input, ResponseEnvelope.parser())
                    ?: error("daemon 提前关闭连接（脚本可能运行到一半 daemon 崩溃）")
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
                    else -> error("意外的响应类型：${resp.bodyCase}")
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
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
                ?: error("daemon 提前关闭连接")
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
            // 读 ShutdownResponse 当作确认；忽略内容
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
