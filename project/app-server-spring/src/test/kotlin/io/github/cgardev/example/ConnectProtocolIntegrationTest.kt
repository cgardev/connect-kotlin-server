package io.github.cgardev.example

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.protobuf.Any
import io.github.cgardev.example.v1.AnyEnvelope
import io.github.cgardev.example.v1.CountRequest
import io.github.cgardev.example.v1.CountResponse
import io.github.cgardev.example.v1.EchoRequest
import io.github.cgardev.example.v1.EchoResponse
import io.github.cgardev.example.v1.FailRequest
import io.github.cgardev.library.connect.ConnectServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Runs every assertion against both demo services — `EchoService` (Java API) and
 * `EchoKotlinService` (Kotlin coroutine API) — to prove they behave identically.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ParameterizedTest(name = "{0}")
@ValueSource(strings = ["EchoService", "EchoKotlinService"])
annotation class BothServices

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["connect.server.port=0"],
)
class ConnectProtocolIntegrationTest {

    @Autowired
    private lateinit var connectServer: ConnectServer

    private val port: Int get() = connectServer.boundPort

    private val client: HttpClient = HttpClient.newHttpClient()
    private val mapper = ObjectMapper()

    private fun url(service: String, method: String) =
        "http://localhost:$port/cgardev.example.v1.$service/$method"

    private fun post(service: String, method: String, contentType: String, body: ByteArray): HttpResponse<ByteArray> {
        val request = HttpRequest.newBuilder(URI.create(url(service, method)))
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray())
    }

    @BothServices
    fun `connect unary over proto`(service: String) {
        val body = EchoRequest.newBuilder().setMessage("hello").build().toByteArray()
        val response = post(service, "Echo", "application/proto", body)

        assertEquals(200, response.statusCode())
        assertTrue(response.headers().firstValue("content-type").get().startsWith("application/proto"))
        assertEquals("echo: hello", EchoResponse.parseFrom(response.body()).message)
    }

    @BothServices
    fun `connect unary over json`(service: String) {
        val response = post(service, "Echo", "application/json", """{"message":"world"}""".toByteArray())

        assertEquals(200, response.statusCode())
        assertTrue(response.headers().firstValue("content-type").get().startsWith("application/json"))
        assertEquals("echo: world", mapper.readTree(response.body()).get("message").asText())
    }

    @BothServices
    fun `connect unary error carries code message and details`(service: String) {
        val response = post(service, "Fail", "application/json", """{"reason":"boom"}""".toByteArray())

        assertEquals(400, response.statusCode())
        assertTrue(response.headers().firstValue("content-type").get().startsWith("application/json"))
        val json: JsonNode = mapper.readTree(response.body())
        assertEquals("invalid_argument", json.get("code").asText())
        assertTrue(json.get("message").asText().contains("boom"))
        val detail = json.get("details").get(0)
        assertEquals("google.rpc.ErrorInfo", detail.get("type").asText())
        assertTrue(detail.has("value"))
    }

    @BothServices
    fun `idempotent unary over GET`(service: String) {
        val request = HttpRequest.newBuilder(URI.create(url(service, "GetServerInfo") + "?encoding=json&message=%7B%7D"))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())

        assertEquals(200, response.statusCode())
        assertEquals("connect-kotlin-server", mapper.readTree(response.body()).get("name").asText())
    }

    @BothServices
    fun `grpc-web unary returns message frame and ok trailer`(service: String) {
        val requestMessage = EchoRequest.newBuilder().setMessage("grpcweb").build().toByteArray()
        val response = post(service, "Echo", "application/grpc-web+proto", envelope(0, requestMessage))

        assertEquals(200, response.statusCode())
        assertTrue(response.headers().firstValue("content-type").get().startsWith("application/grpc-web+proto"))

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
    fun `connect server streaming yields messages and end of stream`(service: String) {
        val requestMessage = CountRequest.newBuilder().setTo(3).build().toByteArray()
        val response = post(service, "Count", "application/connect+proto", envelope(0, requestMessage))

        assertEquals(200, response.statusCode())
        val frames = readFrames(response.body())
        assertEquals(4, frames.size)
        assertEquals(listOf(1, 2, 3), frames.take(3).map { CountResponse.parseFrom(it.data).number })

        val endStream = frames[3]
        assertEquals(0x02, endStream.flags)
        val payload = mapper.readTree(endStream.data)
        assertFalse(payload.has("error"), "end-of-stream should omit error on success")
    }

    @BothServices
    fun `unknown method is unimplemented`(service: String) {
        val response = post(service, "DoesNotExist", "application/proto", ByteArray(0))

        assertEquals(501, response.statusCode())
        assertEquals("unimplemented", mapper.readTree(response.body()).get("code").asText())
    }

    @BothServices
    fun `unsupported request compression is rejected`(service: String) {
        val request = HttpRequest.newBuilder(URI.create(url(service, "Echo")))
            .header("Content-Type", "application/json")
            .header("Content-Encoding", "br")
            .POST(HttpRequest.BodyPublishers.ofByteArray("""{"message":"x"}""".toByteArray()))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())

        assertEquals(501, response.statusCode())
        assertEquals("unimplemented", mapper.readTree(response.body()).get("code").asText())
    }

    @BothServices
    fun `propagates request metadata into a response header and trailer (connect unary)`(service: String) {
        val request = HttpRequest.newBuilder(URI.create(url(service, "Echo")))
            .header("Content-Type", "application/proto")
            .header("x-echo", "hi")
            .POST(HttpRequest.BodyPublishers.ofByteArray(EchoRequest.newBuilder().setMessage("m").build().toByteArray()))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())

        assertEquals(200, response.statusCode())
        assertEquals("hi", response.headers().firstValue("x-echo-header").orElse(null))
        assertEquals("hi", response.headers().firstValue("Trailer-x-echo-trailer").orElse(null))
    }

    @BothServices
    fun `propagates metadata into leading header and grpc-web trailer frame`(service: String) {
        val request = HttpRequest.newBuilder(URI.create(url(service, "Echo")))
            .header("Content-Type", "application/grpc-web+proto")
            .header("x-echo", "gw")
            .POST(HttpRequest.BodyPublishers.ofByteArray(envelope(0, EchoRequest.newBuilder().setMessage("m").build().toByteArray())))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())

        assertEquals(200, response.statusCode())
        assertEquals("gw", response.headers().firstValue("x-echo-header").orElse(null))
        val frames = readFrames(response.body())
        val trailer = String(frames.last().data, StandardCharsets.US_ASCII)
        assertEquals(0x80, frames.last().flags)
        assertTrue(trailer.contains("grpc-status: 0"), trailer)
        assertTrue(trailer.contains("x-echo-trailer: gw"), trailer)
    }

    @BothServices
    fun `error over grpc-web is a trailer frame with status and details`(service: String) {
        val response = post(service, "Fail", "application/grpc-web+proto", envelope(0, FailRequest.newBuilder().setReason("boom").build().toByteArray()))

        assertEquals(200, response.statusCode())
        val frames = readFrames(response.body())
        assertEquals(1, frames.size)
        assertEquals(0x80, frames[0].flags)
        val trailer = String(frames[0].data, StandardCharsets.US_ASCII)
        assertTrue(trailer.contains("grpc-status: 3"), trailer)
        assertTrue(trailer.contains("grpc-message:") && trailer.contains("boom"), trailer)
        assertTrue(trailer.contains("grpc-status-details-bin:"), trailer)
    }

    @BothServices
    fun `error over connect streaming is an end-of-stream error`(service: String) {
        val response = post(service, "Fail", "application/connect+proto", envelope(0, FailRequest.newBuilder().setReason("kaboom").build().toByteArray()))

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
        for ((code, httpStatus, wireName) in cases) {
            val response = post(service, "Fail", "application/json", """{"grpcCode":$code}""".toByteArray())
            assertEquals(httpStatus, response.statusCode(), "code $code")
            assertEquals(wireName, mapper.readTree(response.body()).get("code").asText(), "code $code")
        }
    }

    @BothServices
    fun `round-trips a protobuf Any over proto`(service: String) {
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
    fun `round-trips a protobuf Any over json`(service: String) {
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

    @BothServices
    fun `handles many concurrent requests without cross-talk`(service: String) {
        val count = 64
        val pool = Executors.newFixedThreadPool(16)
        try {
            val tasks = (0 until count).map { i ->
                Callable {
                    val body = EchoRequest.newBuilder().setMessage("c$i").build().toByteArray()
                    val response = post(service, "Echo", "application/proto", body)
                    assertEquals(200, response.statusCode())
                    EchoResponse.parseFrom(response.body()).message
                }
            }
            val results = pool.invokeAll(tasks).map { it.get() }
            assertEquals((0 until count).map { "echo: c$it" }.toSet(), results.toSet())
        } finally {
            pool.shutdownNow()
        }
    }

    @BothServices
    fun `server streaming over grpc-web yields messages then trailer`(service: String) {
        val request = CountRequest.newBuilder().setTo(5).build().toByteArray()
        val response = post(service, "Count", "application/grpc-web+proto", envelope(0, request))

        assertEquals(200, response.statusCode())
        val frames = readFrames(response.body())
        assertEquals(6, frames.size)
        assertEquals((1..5).toList(), frames.take(5).map { CountResponse.parseFrom(it.data).number })
        assertEquals(0x80, frames[5].flags)
        assertTrue(String(frames[5].data, StandardCharsets.US_ASCII).contains("grpc-status: 0"))
    }

    @BothServices
    fun `empty server stream emits only the end-of-stream frame`(service: String) {
        val request = CountRequest.newBuilder().setTo(0).build().toByteArray()
        val response = post(service, "Count", "application/connect+proto", envelope(0, request))

        assertEquals(200, response.statusCode())
        val frames = readFrames(response.body())
        assertEquals(1, frames.size)
        assertEquals(0x02, frames[0].flags)
    }

    @BothServices
    fun `large server stream preserves order and count`(service: String) {
        val count = 250
        val request = CountRequest.newBuilder().setTo(count).build().toByteArray()
        val response = post(service, "Count", "application/connect+proto", envelope(0, request))

        assertEquals(200, response.statusCode())
        val frames = readFrames(response.body())
        assertEquals(count + 1, frames.size)
        assertEquals((1..count).toList(), frames.take(count).map { CountResponse.parseFrom(it.data).number })
        assertEquals(0x02, frames.last().flags)
    }

    private data class Frame(val flags: Int, val data: ByteArray)

    private fun envelope(flags: Int, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(flags)
        out.write((data.size ushr 24) and 0xFF)
        out.write((data.size ushr 16) and 0xFF)
        out.write((data.size ushr 8) and 0xFF)
        out.write(data.size and 0xFF)
        out.write(data)
        return out.toByteArray()
    }

    private fun readFrames(bytes: ByteArray): List<Frame> {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val frames = ArrayList<Frame>()
        while (true) {
            val flags = input.read()
            if (flags == -1) break
            val length = input.readInt()
            val data = ByteArray(length)
            input.readFully(data)
            frames.add(Frame(flags, data))
        }
        return frames
    }
}
