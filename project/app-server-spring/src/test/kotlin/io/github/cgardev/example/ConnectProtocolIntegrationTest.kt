package io.github.cgardev.example

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.cgardev.example.v1.CountRequest
import io.github.cgardev.example.v1.CountResponse
import io.github.cgardev.example.v1.EchoRequest
import io.github.cgardev.example.v1.EchoResponse
import io.github.cgardev.library.connect.ConnectServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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

    private fun url(method: String) = "http://localhost:$port/cgardev.example.v1.EchoService/$method"

    private fun post(method: String, contentType: String, body: ByteArray): HttpResponse<ByteArray> {
        val request = HttpRequest.newBuilder(URI.create(url(method)))
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray())
    }

    @Test
    fun `connect unary over proto`() {
        val body = EchoRequest.newBuilder().setMessage("hello").build().toByteArray()
        val response = post("Echo", "application/proto", body)

        assertEquals(200, response.statusCode())
        assertTrue(response.headers().firstValue("content-type").get().startsWith("application/proto"))
        assertEquals("echo: hello", EchoResponse.parseFrom(response.body()).message)
    }

    @Test
    fun `connect unary over json`() {
        val response = post("Echo", "application/json", """{"message":"world"}""".toByteArray())

        assertEquals(200, response.statusCode())
        assertTrue(response.headers().firstValue("content-type").get().startsWith("application/json"))
        assertEquals("echo: world", mapper.readTree(response.body()).get("message").asText())
    }

    @Test
    fun `connect unary error carries code message and details`() {
        val response = post("Fail", "application/json", """{"reason":"boom"}""".toByteArray())

        assertEquals(400, response.statusCode())
        assertTrue(response.headers().firstValue("content-type").get().startsWith("application/json"))
        val json: JsonNode = mapper.readTree(response.body())
        assertEquals("invalid_argument", json.get("code").asText())
        assertTrue(json.get("message").asText().contains("boom"))
        val detail = json.get("details").get(0)
        assertEquals("google.rpc.ErrorInfo", detail.get("type").asText())
        assertTrue(detail.has("value"))
    }

    @Test
    fun `idempotent unary over GET`() {
        val request = HttpRequest.newBuilder(URI.create(url("GetServerInfo") + "?encoding=json&message=%7B%7D"))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())

        assertEquals(200, response.statusCode())
        assertEquals("connect-kotlin-server", mapper.readTree(response.body()).get("name").asText())
    }

    @Test
    fun `grpc-web unary returns message frame and ok trailer`() {
        val requestMessage = EchoRequest.newBuilder().setMessage("grpcweb").build().toByteArray()
        val response = post("Echo", "application/grpc-web+proto", envelope(0, requestMessage))

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

    @Test
    fun `connect server streaming yields messages and end of stream`() {
        val requestMessage = CountRequest.newBuilder().setTo(3).build().toByteArray()
        val response = post("Count", "application/connect+proto", envelope(0, requestMessage))

        assertEquals(200, response.statusCode())
        val frames = readFrames(response.body())
        assertEquals(4, frames.size)
        assertEquals(listOf(1, 2, 3), frames.take(3).map { CountResponse.parseFrom(it.data).number })

        val endStream = frames[3]
        assertEquals(0x02, endStream.flags)
        val payload = mapper.readTree(endStream.data)
        assertFalse(payload.has("error"), "end-of-stream should omit error on success")
    }

    @Test
    fun `unknown method is unimplemented`() {
        val response = post("DoesNotExist", "application/proto", ByteArray(0))

        assertEquals(501, response.statusCode())
        assertEquals("unimplemented", mapper.readTree(response.body()).get("code").asText())
    }

    @Test
    fun `unsupported request compression is rejected`() {
        val request = HttpRequest.newBuilder(URI.create(url("Echo")))
            .header("Content-Type", "application/json")
            .header("Content-Encoding", "br")
            .POST(HttpRequest.BodyPublishers.ofByteArray("""{"message":"x"}""".toByteArray()))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())

        assertEquals(501, response.statusCode())
        assertEquals("unimplemented", mapper.readTree(response.body()).get("code").asText())
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
