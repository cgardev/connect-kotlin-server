package io.github.cgardev.example.protocol

import io.github.cgardev.example.v1.EchoRequest
import io.github.cgardev.example.v1.EchoResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Connect unary — the non-enveloped request/response path — in both the
 * proto-binary and JSON encodings, plus the idempotent GET form.
 */
class ConnectUnaryTest : ProtocolTestSupport() {

    @BothServices
    fun `echoes a unary message over proto`(service: String) {
        val body = EchoRequest.newBuilder().setMessage("hello").build().toByteArray()

        val response = post(service, "Echo", "application/proto", body)

        assertEquals(200, response.statusCode())
        assertTrue(response.headers().firstValue("content-type").get().startsWith("application/proto"))
        assertEquals("echo: hello", EchoResponse.parseFrom(response.body()).message)
    }

    @BothServices
    fun `echoes a unary message over json`(service: String) {
        val response = post(service, "Echo", "application/json", """{"message":"world"}""".toByteArray())

        assertEquals(200, response.statusCode())
        assertTrue(response.headers().firstValue("content-type").get().startsWith("application/json"))
        assertEquals("echo: world", mapper.readTree(response.body()).get("message").asText())
    }

    @BothServices
    fun `serves an idempotent unary call over GET`(service: String) {
        val response = get(service, "GetServerInfo", "?encoding=json&message=%7B%7D")

        assertEquals(200, response.statusCode())
        assertEquals("connect-kotlin-server", mapper.readTree(response.body()).get("name").asText())
    }
}
