package io.github.cgardev.library.connect.framing

import io.github.cgardev.library.connect.error.ConnectCode
import io.github.cgardev.library.connect.error.ConnectException
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Length-prefixed message framing shared by Connect streaming and gRPC-Web.
 * Each frame is `[flags:1][length:4 big-endian][data:length]`; `length` counts
 * the data only and excludes the 5-byte prefix.
 */
object Envelope {

    /** Bit 0: the message data is compressed. */
    const val FLAG_COMPRESSED: Int = 0x01

    /** Bit 1 (Connect streaming): this is the end-of-stream envelope (JSON payload). */
    const val FLAG_CONNECT_END_STREAM: Int = 0x02

    /** Bit 7 (gRPC-Web): this is the trailer frame (HTTP/1 header block payload). */
    const val FLAG_GRPC_WEB_TRAILER: Int = 0x80

    data class Frame(val flags: Int, val data: ByteArray)

    fun writeFrame(out: OutputStream, flags: Int, data: ByteArray) {
        val length = data.size
        out.write(flags and 0xFF)
        out.write((length ushr 24) and 0xFF)
        out.write((length ushr 16) and 0xFF)
        out.write((length ushr 8) and 0xFF)
        out.write(length and 0xFF)
        out.write(data)
    }

    /** Reads all frames until the stream ends. A truncated frame is a protocol error. */
    fun readAll(input: InputStream): List<Frame> {
        val data = DataInputStream(input)
        val frames = ArrayList<Frame>()
        while (true) {
            val flags = data.read()
            if (flags == -1) break
            val length = try {
                readLength(data)
            } catch (e: EOFException) {
                throw ConnectException(ConnectCode.INVALID_ARGUMENT, "truncated envelope prefix", cause = e)
            }
            val payload = ByteArray(length)
            try {
                data.readFully(payload)
            } catch (e: EOFException) {
                throw ConnectException(ConnectCode.INVALID_ARGUMENT, "truncated envelope payload", cause = e)
            }
            frames.add(Frame(flags, payload))
        }
        return frames
    }

    private fun readLength(data: DataInputStream): Int {
        val length = data.readInt().toLong() and 0xFFFFFFFFL
        if (length > Int.MAX_VALUE.toLong()) {
            throw ConnectException(ConnectCode.RESOURCE_EXHAUSTED, "envelope length exceeds limit")
        }
        return length.toInt()
    }
}
