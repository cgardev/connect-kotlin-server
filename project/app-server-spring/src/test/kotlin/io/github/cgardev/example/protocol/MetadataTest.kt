package io.github.cgardev.example.protocol

import io.github.cgardev.example.v1.EchoRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.charset.StandardCharsets

/**
 * Request metadata round-trips. The demo service copies the request header
 * `x-echo` into a response header (`x-echo-header`) and a trailer
 * (`x-echo-trailer`). Connect unary carries the trailer as a `Trailer-`-prefixed
 * response header; gRPC-Web carries it inside the trailer frame.
 */
class MetadataTest : ProtocolTestSupport() {

    @BothServices
    fun `connect unary maps request metadata to a response header and trailer`(service: String) {
        val body = EchoRequest.newBuilder().setMessage("m").build().toByteArray()

        val response = post(service, "Echo", "application/proto", body, mapOf("x-echo" to "hi"))

        assertEquals(200, response.statusCode())
        assertEquals("hi", response.headers().firstValue("x-echo-header").orElse(null))
        assertEquals("hi", response.headers().firstValue("Trailer-x-echo-trailer").orElse(null))
    }

    @BothServices
    fun `grpc-web maps request metadata to a leading header and trailer frame`(service: String) {
        val body = envelope(0, EchoRequest.newBuilder().setMessage("m").build().toByteArray())

        val response = post(service, "Echo", "application/grpc-web+proto", body, mapOf("x-echo" to "gw"))

        assertEquals(200, response.statusCode())
        assertEquals("gw", response.headers().firstValue("x-echo-header").orElse(null))

        val frames = readFrames(response.body())
        val trailer = String(frames.last().data, StandardCharsets.US_ASCII)
        assertEquals(0x80, frames.last().flags)
        assertTrue(trailer.contains("grpc-status: 0"), trailer)
        assertTrue(trailer.contains("x-echo-trailer: gw"), trailer)
    }
}
