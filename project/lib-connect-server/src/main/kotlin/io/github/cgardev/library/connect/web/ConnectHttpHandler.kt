package io.github.cgardev.library.connect.web

import io.github.cgardev.library.connect.transport.ConnectHttpRequest
import io.github.cgardev.library.connect.transport.ConnectHttpResponse
import java.nio.charset.StandardCharsets

/**
 * The transport-neutral request entry point: applies CORS, answers a liveness
 * probe, dispatches RPC requests to the [ConnectDispatcher], and returns `404`
 * for anything else. A transport adapter (such as the Netty server) is only
 * responsible for translating its native request/response types to the
 * [ConnectHttpRequest]/[ConnectHttpResponse] abstractions and calling [handle].
 */
class ConnectHttpHandler(
    private val dispatcher: ConnectDispatcher,
    private val cors: ConnectCors,
) {

    fun handle(request: ConnectHttpRequest, response: ConnectHttpResponse) {
        if (cors.handlePreflight(request, response)) return
        cors.applyResponseHeaders(request, response)

        when {
            dispatcher.handles(request) -> dispatcher.handle(request, response)
            isHealthProbe(request) -> {
                response.setStatus(200)
                response.setHeader("Content-Type", "text/plain; charset=utf-8")
                response.output.write("ok".toByteArray(StandardCharsets.UTF_8))
            }
            else -> response.setStatus(404)
        }
    }

    private fun isHealthProbe(request: ConnectHttpRequest): Boolean =
        "GET".equals(request.method, ignoreCase = true) && request.path in HEALTH_PATHS

    private companion object {
        private val HEALTH_PATHS = setOf("", "health", "healthz")
    }
}
