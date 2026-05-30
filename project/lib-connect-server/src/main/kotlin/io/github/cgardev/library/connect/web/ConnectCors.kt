package io.github.cgardev.library.connect.web

import io.github.cgardev.library.connect.config.ConnectServerConfig
import io.github.cgardev.library.connect.transport.ConnectHttpRequest
import io.github.cgardev.library.connect.transport.ConnectHttpResponse

/**
 * CORS handling for browser-based Connect / gRPC-Web clients. Permits the
 * protocol headers and exposes the headers that carry Connect and gRPC-Web
 * response metadata. Transport-neutral — no framework dependency.
 *
 * The wildcard origin (`*`) only matches arbitrary origins when credentials are
 * disabled. With [ConnectServerConfig.Cors.allowCredentials] enabled, a request
 * is allowed only when its `Origin` is explicitly listed, never via `*` — this
 * avoids reflecting an attacker-controlled origin together with
 * `Access-Control-Allow-Credentials: true`.
 */
class ConnectCors(private val cors: ConnectServerConfig.Cors) {

    /** Adds the CORS response headers for a (non-preflight) cross-origin request. */
    fun applyResponseHeaders(request: ConnectHttpRequest, response: ConnectHttpResponse) {
        val origin = request.header("Origin") ?: return
        if (!isOriginAllowed(origin)) return
        response.setHeader("Access-Control-Allow-Origin", allowedOriginValue(origin))
        response.addHeader("Vary", "Origin")
        if (cors.allowCredentials) {
            response.setHeader("Access-Control-Allow-Credentials", "true")
        }
        response.setHeader("Access-Control-Expose-Headers", EXPOSED_HEADERS)
    }

    /**
     * Handles a CORS preflight request. Returns true when the request was a
     * preflight and a `204` response has been written (no further handling).
     */
    fun handlePreflight(request: ConnectHttpRequest, response: ConnectHttpResponse): Boolean {
        if (!"OPTIONS".equals(request.method, ignoreCase = true)) return false
        if (request.header("Access-Control-Request-Method") == null) return false

        applyResponseHeaders(request, response)
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.setHeader("Access-Control-Allow-Headers", request.header("Access-Control-Request-Headers") ?: ALLOWED_HEADERS)
        response.setHeader("Access-Control-Max-Age", cors.maxAgeSeconds.toString())
        if (cors.allowPrivateNetwork &&
            "true".equals(request.header("Access-Control-Request-Private-Network"), ignoreCase = true)
        ) {
            response.setHeader("Access-Control-Allow-Private-Network", "true")
        }
        response.setStatus(204)
        return true
    }

    private fun isOriginAllowed(origin: String): Boolean =
        if (cors.allowCredentials) {
            cors.allowedOrigins.contains(origin)
        } else {
            cors.allowedOrigins.contains("*") || cors.allowedOrigins.contains(origin)
        }

    private fun allowedOriginValue(origin: String): String =
        if (cors.allowedOrigins.contains("*") && !cors.allowCredentials) "*" else origin

    private companion object {
        private const val ALLOWED_HEADERS =
            "Content-Type, Connect-Protocol-Version, Connect-Timeout-Ms, " +
                "Connect-Content-Encoding, Connect-Accept-Encoding, " +
                "Grpc-Timeout, Grpc-Encoding, Grpc-Accept-Encoding, " +
                "X-Grpc-Web, X-User-Agent, Authorization"
        private const val EXPOSED_HEADERS =
            "Content-Encoding, Connect-Content-Encoding, Connect-Accept-Encoding, " +
                "Grpc-Status, Grpc-Message, Grpc-Status-Details-Bin"
    }
}
