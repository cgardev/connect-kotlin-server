package io.github.cgardev.example.protocol

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.cgardev.example.demo.EchoService
import io.github.cgardev.example.demo.KotlinEchoService
import io.github.cgardev.example.demo.MetadataEchoInterceptor
import io.github.cgardev.library.connect.ConnectServer
import io.github.cgardev.library.connect.config.ConnectServerConfig
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Runs a test against both demo services — `EchoService` (Java API) and
 * `EchoKotlinService` (Kotlin coroutine API) — to prove they behave identically.
 * The service name arrives as the single test parameter.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ParameterizedTest(name = "{0}")
@ValueSource(strings = ["EchoService", "EchoKotlinService"])
annotation class BothServices

/**
 * Shared fixture for the protocol integration suites. Each suite starts its own
 * in-process Connect server on an ephemeral port, hosting the same beans the
 * Spring application wires — the Java `EchoService`, the Kotlin coroutine
 * `KotlinEchoService`, and the metadata-echo interceptor — and exposes the HTTP,
 * framing and parsing helpers the protocols are tested through.
 *
 * The server is built directly rather than through `@SpringBootTest`: it keeps
 * each suite fast and fully independent, and avoids the test context lifecycle
 * pausing the server between classes. Spring's own wiring is covered separately
 * by AppServerSpringTests and the autoconfigure module's tests.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ProtocolTestSupport {

    private lateinit var server: ConnectServer
    private val client: HttpClient = HttpClient.newHttpClient()
    protected val mapper: ObjectMapper = ObjectMapper()

    private val port: Int get() = server.boundPort

    @BeforeAll
    fun startServer() {
        server = ConnectServer(
            services = listOf(EchoService(), KotlinEchoService()),
            interceptors = listOf(MetadataEchoInterceptor()),
            config = ConnectServerConfig(host = "localhost", port = 0),
        )
        server.start()
    }

    @AfterAll
    fun stopServer() {
        server.close()
    }

    protected fun url(service: String, method: String): String =
        "http://localhost:$port/cgardev.example.v1.$service/$method"

    /** POSTs [body] to `<service>/<method>` with the given content type and optional extra headers. */
    protected fun post(
        service: String,
        method: String,
        contentType: String,
        body: ByteArray,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse<ByteArray> {
        val builder = HttpRequest.newBuilder(URI.create(url(service, method)))
            .header("Content-Type", contentType)
        headers.forEach { (name, value) -> builder.header(name, value) }
        return send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build())
    }

    /** GETs `<service>/<method><query>` (the query string includes its leading `?`). */
    protected fun get(service: String, method: String, query: String): HttpResponse<ByteArray> =
        send(HttpRequest.newBuilder(URI.create(url(service, method) + query)).GET().build())

    protected fun send(request: HttpRequest): HttpResponse<ByteArray> =
        client.send(request, HttpResponse.BodyHandlers.ofByteArray())

    /** A single Connect/gRPC-Web envelope frame: a flags byte, a 4-byte big-endian length, then the payload. */
    protected data class Frame(val flags: Int, val data: ByteArray)

    /** Wraps [data] in one envelope frame with the given [flags]. */
    protected fun envelope(flags: Int, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(flags)
        out.write((data.size ushr 24) and 0xFF)
        out.write((data.size ushr 16) and 0xFF)
        out.write((data.size ushr 8) and 0xFF)
        out.write(data.size and 0xFF)
        out.write(data)
        return out.toByteArray()
    }

    /** Parses an enveloped response body back into its frames. */
    protected fun readFrames(bytes: ByteArray): List<Frame> {
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
