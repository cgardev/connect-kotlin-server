package io.github.cgardev.library.connect.web

import io.github.cgardev.library.connect.transport.ConnectHttpRequest
import io.github.cgardev.library.connect.transport.ConnectHttpResponse
import org.slf4j.LoggerFactory
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

    private val log = LoggerFactory.getLogger(ConnectHttpHandler::class.java)

    fun handle(request: ConnectHttpRequest, response: ConnectHttpResponse) {
        if (cors.handlePreflight(request, response)) return

        // The dispatcher resets the response before writing an error, which would drop
        // the CORS headers applied below. Wrapping the response re-applies them after
        // every reset, so error responses still carry Access-Control-Allow-Origin and a
        // browser surfaces the real RPC status instead of an opaque CORS failure.
        val corsAware: ConnectHttpResponse = CorsPreservingResponse(response, request, cors)
        cors.applyResponseHeaders(request, corsAware)

        try {
            when {
                dispatcher.handles(request) -> dispatcher.handle(request, corsAware)
                isHealthProbe(request) -> {
                    corsAware.setStatus(200)
                    corsAware.setHeader("Content-Type", "text/plain; charset=utf-8")
                    corsAware.output.write("ok".toByteArray(StandardCharsets.UTF_8))
                }
                else -> corsAware.setStatus(404)
            }
        } catch (e: Exception) {
            // Last-resort guard: render a CORS-preserving 500 rather than let the error
            // propagate to a bare response that drops the cross-origin headers.
            log.error("Unhandled error handling {} {}", request.method, request.path, e)
            if (!corsAware.isCommitted) {
                corsAware.reset()
                corsAware.setStatus(500)
            }
        }
    }

    private fun isHealthProbe(request: ConnectHttpRequest): Boolean =
        "GET".equals(request.method, ignoreCase = true) && request.path in HEALTH_PATHS

    private companion object {
        private val HEALTH_PATHS = setOf("", "health", "healthz")
    }
}
