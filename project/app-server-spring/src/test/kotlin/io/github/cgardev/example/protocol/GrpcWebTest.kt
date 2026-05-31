package io.github.cgardev.example.protocol

import io.github.cgardev.example.v1.EchoRequest
import io.github.cgardev.example.v1.EchoResponse
import io.github.cgardev.example.v1.FailRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.charset.StandardCharsets

/**
 * gRPC-Web unary framing: a successful call yields a message frame followed by a
 * trailer frame (`grpc-status: 0`), and a failed call yields a single trailer
 * frame carrying the status, message and binary details. The trailer frame is
 * flagged `0x80`.
 */
class GrpcWebTest : ProtocolTestSupport() {

    private val contentType = "application/grpc-web+proto"

    @BothServices
    fun `unary returns a message frame and an ok trailer`(service: String) {
        val request = EchoRequest.newBuilder().setMessage("grpcweb").build().toByteArray()

        val response = post(service, "Echo", contentType, envelope(0, request))

        assertEquals(200, response.statusCode())
        assertTrue(response.headers().firstValue("content-type").get().startsWith(contentType))

        val frames = readFrames(response.body())
        assertEquals(2, frames.size)
        assertEquals(0, frames[0].flags)
        assertEquals("echo: grpcweb", EchoResponse.parseFrom(frames[0].data).message)

        val trailer = frames[1]
        assertEquals(0x80, trailer.flags)
        val trailerText = String(trailer.data, StandardCharsets.US_ASCII)
        assertTrue(trailerText.contains("grpc-status: 0"), "trailer was: $trailerText")
    }

    @BothServices
    fun `error is a single trailer frame with status, message and details`(service: String) {
        val request = FailRequest.newBuilder().setReason("boom").build().toByteArray()

        val response = post(service, "Fail", contentType, envelope(0, request))

        assertEquals(200, response.statusCode())
        val frames = readFrames(response.body())
        assertEquals(1, frames.size)
        assertEquals(0x80, frames[0].flags)

        val trailer = String(frames[0].data, StandardCharsets.US_ASCII)
        assertTrue(trailer.contains("grpc-status: 3"), trailer)
        assertTrue(trailer.contains("grpc-message:") && trailer.contains("boom"), trailer)
        assertTrue(trailer.contains("grpc-status-details-bin:"), trailer)
    }
}
