package io.github.nebula.ktx.proto.v1

import com.google.protobuf.MessageLite
import com.google.protobuf.Parser
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Length-prefixed frame codec: 4-byte big-endian length followed by payload.
 *
 * Why length-prefix over protobuf's own delimited form: protobuf delimited
 * uses a varint length, which is harder to diagnose against occasional data
 * corruption than a self-describing fixed length. The 4-byte length caps at
 * 4 GB, far above any realistic request (script sources are at most a few
 * dozen KB), and the header costs us essentially one line of code with no
 * third-party dependency (grpc / netty are far too heavy).
 *
 * Safety cap: [MAX_FRAME_BYTES] rejects frames larger than 32 MB to prevent
 * a malicious client from OOM-ing the server.
 */
object Frames {
    private const val MAX_FRAME_BYTES = 32 * 1024 * 1024

    /**
     * Reads a single frame and parses it via [parser]. Returns null if the
     * stream is closed or hits EOF.
     */
    fun <T : MessageLite> read(input: InputStream, parser: Parser<T>): T? {
        val din = DataInputStream(input)
        val length = try {
            din.readInt()
        } catch (e: EOFException) {
            return null
        }
        require(length in 0..MAX_FRAME_BYTES) { "invalid frame length: $length (max $MAX_FRAME_BYTES)" }
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

/** Current protocol version. When CLI and daemon disagree the daemon refuses
 *  service, prompting the client to kill the old daemon and start a new one. */
const val PROTOCOL_VERSION: Int = 1
