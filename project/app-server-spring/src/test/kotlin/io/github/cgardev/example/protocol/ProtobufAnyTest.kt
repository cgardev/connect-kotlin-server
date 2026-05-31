package io.github.cgardev.example.protocol

import com.google.protobuf.Any
import io.github.cgardev.example.v1.AnyEnvelope
import io.github.cgardev.example.v1.EchoRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * `google.protobuf.Any` survives the round-trip in both proto and JSON. The
 * server echoes the Any back untouched, so a faithful round-trip proves each
 * encoding carries the packed message end to end (JSON uses the `@type` URL).
 */
class ProtobufAnyTest : ProtocolTestSupport() {

    @BothServices
    fun `round-trips an Any over proto`(service: String) {
        val packed = Any.pack(EchoRequest.newBuilder().setMessage("packed").build())
        val envelope = AnyEnvelope.newBuilder().setPayload(packed).setLabel("x").build()

        val response = post(service, "RoundTrip", "application/proto", envelope.toByteArray())

        assertEquals(200, response.statusCode())
        val out = AnyEnvelope.parseFrom(response.body())
        assertEquals("roundtrip:x", out.label)
        assertTrue(out.payload.`is`(EchoRequest::class.java))
        assertEquals("packed", out.payload.unpack(EchoRequest::class.java).message)
    }

    @BothServices
    fun `round-trips an Any over json`(service: String) {
        val body = """
            {"payload":{"@type":"type.googleapis.com/cgardev.example.v1.EchoRequest","message":"packed"},"label":"y"}
        """.trimIndent()

        val response = post(service, "RoundTrip", "application/json", body.toByteArray())

        assertEquals(200, response.statusCode())
        val json = mapper.readTree(response.body())
        assertEquals("roundtrip:y", json.get("label").asText())

        val payload = json.get("payload")
        assertEquals("type.googleapis.com/cgardev.example.v1.EchoRequest", payload.get("@type").asText())
        assertEquals("packed", payload.get("message").asText())
    }
}
