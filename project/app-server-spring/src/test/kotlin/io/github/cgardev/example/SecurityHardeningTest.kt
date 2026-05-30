package io.github.cgardev.example

import io.github.cgardev.example.demo.EchoService
import io.github.cgardev.library.connect.ConnectServer
import io.github.cgardev.library.connect.config.ConnectServerConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.zip.GZIPOutputStream

/**
 * Regression tests for the hardening applied after the security review: bounded
 * gzip decompression, a bounded envelope length prefix, an idle-connection
 * timeout (slow-loris defence) and CORS that never reflects an arbitrary origin
 * together with credentials.
 */
class SecurityHardeningTest {

    private fun withServer(config: ConnectServerConfig, block: (ConnectServer) -> Unit) {
        val server = ConnectServer(services = listOf(EchoService()), config = config)
        server.start()
        try {
            block(server)
        } finally {
            server.close()
        }
    }

    private fun echoUrl(port: Int) = "http://localhost:$port/cgardev.example.v1.EchoService/Echo"

    @Test
    fun `a gzip bomb is rejected before it exhausts the heap`() {
        // The limit is 1 MiB but the payload decompresses to 64 MiB of zeros; the
        // compressed form is only a few kilobytes, so it passes the raw body check
        // and must be stopped during decompression.
        val config = ConnectServerConfig(host = "localhost", port = 0, readMaxBytes = 1L * 1024 * 1024)
        withServer(config) { server ->
            val bomb = gzip(ByteArray(64 * 1024 * 1024))
            val request = HttpRequest.newBuilder(URI.create(echoUrl(server.boundPort)))
                .header("Content-Type", "application/json")
                .header("Content-Encoding", "gzip")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bomb))
                .build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())

            assertEquals(429, response.statusCode())
            assertTrue(response.body().contains("resource_exhausted"), "expected resource_exhausted, got ${response.body()}")
        }
    }

    @Test
    fun `an oversized envelope length prefix is rejected before allocation`() {
        val config = ConnectServerConfig(host = "localhost", port = 0, readMaxBytes = 1L * 1024 * 1024)
        withServer(config) { server ->
            // Connect-streaming frame: flags=0x00 then a 2,000,000,000-byte length
            // prefix with no payload. A naive reader would allocate ~2 GB here.
            val length = 2_000_000_000
            val frame = byteArrayOf(
                0x00,
                (length ushr 24).toByte(),
                (length ushr 16).toByte(),
                (length ushr 8).toByte(),
                length.toByte(),
            )
            val request = HttpRequest.newBuilder(URI.create(echoUrl(server.boundPort)))
                .header("Content-Type", "application/connect+proto")
                .POST(HttpRequest.BodyPublishers.ofByteArray(frame))
                .build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())

            // Enveloped protocols report the failure in the end-of-stream frame (HTTP 200).
            assertEquals(200, response.statusCode())
            assertTrue(response.body().contains("resource_exhausted"), "expected resource_exhausted, got ${response.body()}")
        }
    }

    @Test
    fun `an idle connection is closed by the server`() {
        val config = ConnectServerConfig(host = "localhost", port = 0, idleTimeoutMillis = 300)
        withServer(config) { server ->
            Socket("localhost", server.boundPort).use { socket ->
                // Generous client timeout: the server must close first, on its own
                // idle timer, without the client ever sending a byte.
                socket.soTimeout = 5_000
                val eof = socket.getInputStream().read()
                assertEquals(-1, eof, "the server should close an idle connection rather than hold it open")
            }
        }
    }

    @Test
    fun `default CORS does not enable credentials`() {
        withServer(ConnectServerConfig(host = "localhost", port = 0)) { server ->
            val response = corsProbe(server.boundPort, origin = "https://app.example.com")

            assertEquals("*", response.headers().firstValue("access-control-allow-origin").orElse(null))
            assertFalse(
                response.headers().firstValue("access-control-allow-credentials").isPresent,
                "credentials must be off by default",
            )
        }
    }

    @Test
    fun `wildcard origin with credentials never reflects an arbitrary origin`() {
        val config = ConnectServerConfig(
            host = "localhost",
            port = 0,
            cors = ConnectServerConfig.Cors(allowedOrigins = listOf("*"), allowCredentials = true),
        )
        withServer(config) { server ->
            val response = corsProbe(server.boundPort, origin = "https://evil.example")

            assertFalse(
                response.headers().firstValue("access-control-allow-origin").isPresent,
                "an attacker origin must not be reflected when credentials are enabled",
            )
        }
    }

    private fun corsProbe(port: Int, origin: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(echoUrl(port)))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .header("Origin", origin)
            .POST(HttpRequest.BodyPublishers.ofString("""{"message":"x"}"""))
            .build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun gzip(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(data) }
        return output.toByteArray()
    }
}
