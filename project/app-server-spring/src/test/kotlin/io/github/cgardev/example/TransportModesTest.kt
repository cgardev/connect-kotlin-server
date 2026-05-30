package io.github.cgardev.example

import io.github.cgardev.example.demo.EchoService
import io.github.cgardev.example.v1.EchoRequest
import io.github.cgardev.example.v1.EchoServiceGrpc
import io.github.cgardev.library.connect.ConnectServer
import io.github.cgardev.library.connect.config.ConnectServerConfig
import io.grpc.ManagedChannelBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Verifies the explicit transport enables: HTTP/1.1 + HTTP/2 on one port (h2c),
 * HTTP/2 only, and a native gRPC port with the HTTP server disabled.
 */
class TransportModesTest {

    private fun withServer(config: ConnectServerConfig, block: (ConnectServer) -> Unit) {
        val server = ConnectServer(services = listOf(EchoService()), config = config)
        server.start()
        try {
            block(server)
        } finally {
            server.close()
        }
    }

    private fun connectUrl(port: Int, method: String) =
        "http://localhost:$port/cgardev.example.v1.EchoService/$method"

    @Test
    fun `h2c serves both HTTP2 and HTTP1 on the same port`() {
        withServer(ConnectServerConfig(host = "localhost", port = 0, http1Enabled = true, http2Enabled = true)) { server ->
            val http2 = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build()
            val getRequest = HttpRequest
                .newBuilder(URI.create("${connectUrl(server.boundPort, "GetServerInfo")}?encoding=json&message=%7B%7D"))
                .GET()
                .build()
            val getResponse = http2.send(getRequest, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, getResponse.statusCode())
            assertEquals(HttpClient.Version.HTTP_2, getResponse.version())

            val http1 = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
            val postRequest = HttpRequest.newBuilder(URI.create(connectUrl(server.boundPort, "Echo")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"message":"h1"}"""))
                .build()
            val postResponse = http1.send(postRequest, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, postResponse.statusCode())
            assertEquals(HttpClient.Version.HTTP_1_1, postResponse.version())
            assertEquals("""{"message":"echo: h1"}""", postResponse.body())
        }
    }

    @Test
    fun `http2-only rejects plain HTTP1 requests`() {
        withServer(ConnectServerConfig(host = "localhost", port = 0, http1Enabled = false, http2Enabled = true)) { server ->
            assertTrue(server.boundPort > 0)
            val http1 = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
            val request = HttpRequest.newBuilder(URI.create(connectUrl(server.boundPort, "Echo")))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"message":"x"}"""))
                .build()
            // The HTTP/2-only port has no HTTP/1.1 codec, so a plain HTTP/1.1 request fails.
            assertThrows<IOException> { http1.send(request, HttpResponse.BodyHandlers.ofString()) }
        }
    }

    @Test
    fun `grpc-only exposes the service over native gRPC with the HTTP server disabled`() {
        val grpcPort = ServerSocket(0).use { it.localPort }
        val config = ConnectServerConfig(
            host = "localhost",
            http1Enabled = false,
            http2Enabled = false,
            grpcEnabled = true,
            grpcPort = grpcPort,
        )
        withServer(config) { server ->
            assertThrows<IllegalStateException> { server.boundPort }
            assertEquals(grpcPort, server.grpcBoundPort)

            val channel = ManagedChannelBuilder.forAddress("localhost", server.grpcBoundPort!!).usePlaintext().build()
            try {
                val response = EchoServiceGrpc.newBlockingStub(channel)
                    .echo(EchoRequest.newBuilder().setMessage("grpc").build())
                assertEquals("echo: grpc", response.message)
            } finally {
                channel.shutdownNow()
            }
        }
    }
}
