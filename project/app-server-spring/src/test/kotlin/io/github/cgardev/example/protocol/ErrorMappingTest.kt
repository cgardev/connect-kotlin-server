package io.github.cgardev.example.protocol

import io.github.cgardev.example.v1.FailRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * How errors surface on the wire: the Connect unary error JSON, the Connect
 * end-of-stream error frame, the gRPC status → HTTP status / wire-code table, and
 * the two request-rejection cases (unknown method, unsupported compression).
 */
class ErrorMappingTest : ProtocolTestSupport() {

    @BothServices
    fun `connect unary error carries code, message and details`(service: String) {
        val response = post(service, "Fail", "application/json", """{"reason":"boom"}""".toByteArray())

        assertEquals(400, response.statusCode())
        assertTrue(response.headers().firstValue("content-type").get().startsWith("application/json"))

        val json = mapper.readTree(response.body())
        assertEquals("invalid_argument", json.get("code").asText())
        assertTrue(json.get("message").asText().contains("boom"))

        val detail = json.get("details").get(0)
        assertEquals("google.rpc.ErrorInfo", detail.get("type").asText())
        assertTrue(detail.has("value"))
    }

    @BothServices
    fun `connect streaming error is an end-of-stream error frame`(service: String) {
        val request = FailRequest.newBuilder().setReason("kaboom").build().toByteArray()

        val response = post(service, "Fail", "application/connect+proto", envelope(0, request))

        assertEquals(200, response.statusCode())
        val frames = readFrames(response.body())
        assertEquals(1, frames.size)
        assertEquals(0x02, frames[0].flags)

        val error = mapper.readTree(frames[0].data).get("error")
        assertEquals("invalid_argument", error.get("code").asText())
        assertTrue(error.get("message").asText().contains("kaboom"))
        assertEquals("google.rpc.ErrorInfo", error.get("details").get(0).get("type").asText())
    }

    @BothServices
    fun `maps gRPC status codes to HTTP status and wire code`(service: String) {
        val cases = listOf(
            Triple(5, 404, "not_found"),
            Triple(7, 403, "permission_denied"),
            Triple(16, 401, "unauthenticated"),
        )
        for ((grpcCode, httpStatus, wireName) in cases) {
            val response = post(service, "Fail", "application/json", """{"grpcCode":$grpcCode}""".toByteArray())

            assertEquals(httpStatus, response.statusCode(), "gRPC code $grpcCode")
            assertEquals(wireName, mapper.readTree(response.body()).get("code").asText(), "gRPC code $grpcCode")
        }
    }

    @BothServices
    fun `an unknown method is unimplemented`(service: String) {
        val response = post(service, "DoesNotExist", "application/proto", ByteArray(0))

        assertEquals(501, response.statusCode())
        assertEquals("unimplemented", mapper.readTree(response.body()).get("code").asText())
    }

    @BothServices
    fun `an unsupported request compression is rejected`(service: String) {
        val response = post(
            service,
            "Echo",
            "application/json",
            """{"message":"x"}""".toByteArray(),
            mapOf("Content-Encoding" to "br"),
        )

        assertEquals(501, response.statusCode())
        assertEquals("unimplemented", mapper.readTree(response.body()).get("code").asText())
    }

    @BothServices
    fun `a unary error response still carries the CORS header`(service: String) {
        val response = post(
            service,
            "Fail",
            "application/json",
            """{"reason":"boom"}""".toByteArray(),
            mapOf("Origin" to "https://app.example.com"),
        )

        // The error path resets the response before writing the body; the cross-origin
        // header must survive so a browser surfaces the real status instead of a CORS error.
        assertEquals(400, response.statusCode())
        assertEquals(
            "*",
            response.headers().firstValue("access-control-allow-origin").orElse(null),
        )
    }
}
