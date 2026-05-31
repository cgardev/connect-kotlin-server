package io.github.cgardev.example.protocol

import io.github.cgardev.example.v1.EchoRequest
import io.github.cgardev.example.v1.EchoResponse
import org.junit.jupiter.api.Assertions.assertEquals
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Many in-flight unary requests must not cross-talk: every response has to match
 * its own request, proving the dispatcher keeps concurrent calls independent.
 */
class ConcurrencyTest : ProtocolTestSupport() {

    @BothServices
    fun `handles many concurrent requests without cross-talk`(service: String) {
        val count = 64
        val pool = Executors.newFixedThreadPool(16)
        try {
            val tasks = (0 until count).map { index ->
                Callable {
                    val body = EchoRequest.newBuilder().setMessage("c$index").build().toByteArray()
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
}
