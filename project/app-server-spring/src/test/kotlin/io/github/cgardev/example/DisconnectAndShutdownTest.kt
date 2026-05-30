package io.github.cgardev.example

import io.github.cgardev.example.v1.CountRequest
import io.github.cgardev.example.v1.CountResponse
import io.github.cgardev.example.v1.EchoRequest
import io.github.cgardev.example.v1.EchoResponse
import io.github.cgardev.example.v1.EchoServiceGrpc
import io.github.cgardev.library.connect.ConnectServer
import io.github.cgardev.library.connect.config.ConnectServerConfig
import io.grpc.Context
import io.grpc.stub.StreamObserver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Regression tests for the lifecycle hardening: an in-flight request must drain
 * on shutdown rather than being cut off (#10), and a client disconnect must
 * cancel the in-process gRPC call rather than leaving its worker thread blocked
 * (#9).
 */
class DisconnectAndShutdownTest {

    @Test
    fun `close drains an in-flight request instead of cutting it off`() {
        val handlerEntered = CountDownLatch(1)
        val slowService = object : EchoServiceGrpc.EchoServiceImplBase() {
            override fun echo(request: EchoRequest, responseObserver: StreamObserver<EchoResponse>) {
                handlerEntered.countDown()
                Thread.sleep(400) // simulate slow work still running when close() is called
                responseObserver.onNext(EchoResponse.newBuilder().setMessage("slow: ${request.message}").build())
                responseObserver.onCompleted()
            }
        }
        val config = ConnectServerConfig(host = "localhost", port = 0, shutdownGraceMillis = 5_000)
        val server = ConnectServer(services = listOf(slowService), config = config)
        server.start()
        val port = server.boundPort

        val response = AtomicReference<HttpResponse<String>>()
        val failure = AtomicReference<Throwable>()
        val caller = Thread {
            try {
                val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/cgardev.example.v1.EchoService/Echo"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"message":"x"}"""))
                    .build()
                response.set(HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()))
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        caller.start()

        // Close only once the handler is actually executing, so the request is in flight.
        assertTrue(handlerEntered.await(2, TimeUnit.SECONDS), "the handler should start before close()")
        server.close() // must block until the in-flight request finishes and its response is written
        caller.join(3_000)

        assertNull(failure.get(), "the in-flight request must not be cut off: ${failure.get()}")
        assertEquals(200, response.get().statusCode())
        assertEquals("""{"message":"slow: x"}""", response.get().body())
    }

    @Test
    fun `a client disconnect cancels the in-process call`() {
        val handlerStarted = CountDownLatch(1)
        val callCancelled = CountDownLatch(1)
        val directExecutor = Executor { it.run() }
        val streamingService = object : EchoServiceGrpc.EchoServiceImplBase() {
            override fun count(request: CountRequest, responseObserver: StreamObserver<CountResponse>) {
                Context.current().addListener(Context.CancellationListener { callCancelled.countDown() }, directExecutor)
                handlerStarted.countDown()
                try {
                    var number = 1
                    // Emit slowly so the call is still in flight when the client disconnects.
                    while (number <= request.to && !Context.current().isCancelled) {
                        responseObserver.onNext(CountResponse.newBuilder().setNumber(number++).build())
                        Thread.sleep(50)
                    }
                    if (!Context.current().isCancelled) responseObserver.onCompleted()
                } catch (e: Exception) {
                    // Cancelled/interrupted: stop quietly.
                }
            }
        }
        val server = ConnectServer(services = listOf(streamingService), config = ConnectServerConfig(host = "localhost", port = 0))
        server.start()
        val port = server.boundPort
        try {
            // A raw socket lets us abort the connection mid-stream.
            Socket("localhost", port).use { socket ->
                val body = connectEnvelope(CountRequest.newBuilder().setTo(1_000).build().toByteArray())
                val headers = buildString {
                    append("POST /cgardev.example.v1.EchoService/Count HTTP/1.1\r\n")
                    append("Host: localhost\r\n")
                    append("Content-Type: application/connect+proto\r\n")
                    append("Content-Length: ${body.size}\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }
                socket.getOutputStream().apply {
                    write(headers.toByteArray(StandardCharsets.US_ASCII))
                    write(body)
                    flush()
                }
                assertTrue(handlerStarted.await(2, TimeUnit.SECONDS), "the streaming handler should start")
                socket.getInputStream().read(ByteArray(16)) // consume part of the stream, then disconnect
            }
            assertTrue(
                callCancelled.await(5, TimeUnit.SECONDS),
                "the server must observe cancellation after the client disconnects",
            )
        } finally {
            server.close()
        }
    }

    /** Wraps a payload in a single Connect/gRPC-Web envelope frame: `[flags=0][len BE]payload`. */
    private fun connectEnvelope(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0)
        out.write((payload.size ushr 24) and 0xFF)
        out.write((payload.size ushr 16) and 0xFF)
        out.write((payload.size ushr 8) and 0xFF)
        out.write(payload.size and 0xFF)
        out.write(payload)
        return out.toByteArray()
    }
}
