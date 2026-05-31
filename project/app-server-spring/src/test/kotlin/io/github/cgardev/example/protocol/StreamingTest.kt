package io.github.cgardev.example.protocol

import io.github.cgardev.example.v1.CountRequest
import io.github.cgardev.example.v1.CountResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.charset.StandardCharsets

/**
 * Server-streaming framing. Connect streaming closes with an end-of-stream frame
 * (`0x02`); gRPC-Web closes with a trailer frame (`0x80`). Both must deliver the
 * messages in order, and the empty and large cases must frame correctly too.
 */
class StreamingTest : ProtocolTestSupport() {

    private fun count(to: Int): ByteArray =
        envelope(0, CountRequest.newBuilder().setTo(to).build().toByteArray())

    @BothServices
    fun `connect streaming yields messages then an end-of-stream frame`(service: String) {
        val response = post(service, "Count", "application/connect+proto", count(3))

        assertEquals(200, response.statusCode())
        val frames = readFrames(response.body())
        assertEquals(4, frames.size)
        assertEquals(listOf(1, 2, 3), frames.take(3).map { CountResponse.parseFrom(it.data).number })

        val endStream = frames[3]
        assertEquals(0x02, endStream.flags)
        assertFalse(mapper.readTree(endStream.data).has("error"), "end-of-stream should omit error on success")
    }

    @BothServices
    fun `grpc-web streaming yields messages then a trailer frame`(service: String) {
        val response = post(service, "Count", "application/grpc-web+proto", count(5))

        assertEquals(200, response.statusCode())
        val frames = readFrames(response.body())
        assertEquals(6, frames.size)
        assertEquals((1..5).toList(), frames.take(5).map { CountResponse.parseFrom(it.data).number })
        assertEquals(0x80, frames[5].flags)
        assertTrue(String(frames[5].data, StandardCharsets.US_ASCII).contains("grpc-status: 0"))
    }

    @BothServices
    fun `an empty stream emits only the end-of-stream frame`(service: String) {
        val response = post(service, "Count", "application/connect+proto", count(0))

        assertEquals(200, response.statusCode())
        val frames = readFrames(response.body())
        assertEquals(1, frames.size)
        assertEquals(0x02, frames[0].flags)
    }

    @BothServices
    fun `a large stream preserves order and count`(service: String) {
        val total = 250

        val response = post(service, "Count", "application/connect+proto", count(total))

        assertEquals(200, response.statusCode())
        val frames = readFrames(response.body())
        assertEquals(total + 1, frames.size)
        assertEquals((1..total).toList(), frames.take(total).map { CountResponse.parseFrom(it.data).number })
        assertEquals(0x02, frames.last().flags)
    }
}
