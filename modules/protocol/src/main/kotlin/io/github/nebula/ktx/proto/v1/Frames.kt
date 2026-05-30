package io.github.nebula.ktx.proto.v1

import com.google.protobuf.MessageLite
import com.google.protobuf.Parser
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * 长度前缀帧编解码：4 字节 big-endian 长度 + payload。
 *
 * 选择背景：选 length-prefix 而非 protobuf 自带的 delimited 是因为后者
 * 用 varint 长度，对偶发损坏数据没自描述长度好诊断。4 字节长度上限 4GB
 * 远超实际请求大小（脚本源码顶多几十 KB），头加进去一行成本，不引第三方
 * 库（grpc / netty 都过重）。
 *
 * 安全上限：[MAX_FRAME_BYTES] 拒收超过 32MB 的帧，防止恶意客户端 OOM。
 */
object Frames {
    private const val MAX_FRAME_BYTES = 32 * 1024 * 1024

    /**
     * 读一个完整帧并交给 [parser] 解析。流被关闭或读到 EOF 时返回 null。
     */
    fun <T : MessageLite> read(input: InputStream, parser: Parser<T>): T? {
        val din = DataInputStream(input)
        val length = try {
            din.readInt()
        } catch (e: EOFException) {
            return null
        }
        require(length in 0..MAX_FRAME_BYTES) { "帧长度异常：$length（上限 $MAX_FRAME_BYTES）" }
        val buf = ByteArray(length)
        din.readFully(buf)
        return parser.parseFrom(buf)
    }

    fun write(output: OutputStream, message: MessageLite) {
        val payload = message.toByteArray()
        val dout = DataOutputStream(output)
        dout.writeInt(payload.size)
        dout.write(payload)
        dout.flush()
    }
}

/** 当前协议版本，CLI / daemon 不一致时拒绝服务，让客户端杀掉旧 daemon 重启新版本。 */
const val PROTOCOL_VERSION: Int = 1
