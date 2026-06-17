package io.github.cgardev.library.connect.web

import io.github.cgardev.library.connect.transport.ConnectHttpRequest
import io.github.cgardev.library.connect.transport.ConnectHttpResponse
import java.io.OutputStream

/**
 * Wraps a [ConnectHttpResponse] so the CORS headers outlive a [reset].
 *
 * The CORS headers are applied up front, before the request is dispatched. The
 * dispatcher, however, clears the buffered status, headers and body with
 * [ConnectHttpResponse.reset] before it writes an error (see
 * `ConnectDispatcher.writeUnaryError`). That reset would also drop the
 * cross-origin headers, so a browser would see the error response without an
 * `Access-Control-Allow-Origin` header and surface it as an opaque CORS failure
 * instead of the real RPC status. Re-applying them after every reset keeps error
 * responses readable by browser clients.
 */
internal class CorsPreservingResponse(
    private val delegate: ConnectHttpResponse,
    private val request: ConnectHttpRequest,
    private val cors: ConnectCors,
) : ConnectHttpResponse {

    override val isCommitted: Boolean get() = delegate.isCommitted

    override fun setStatus(code: Int) = delegate.setStatus(code)

    override fun setHeader(name: String, value: String) = delegate.setHeader(name, value)

    override fun addHeader(name: String, value: String) = delegate.addHeader(name, value)

    override fun reset() {
        delegate.reset()
        cors.applyResponseHeaders(request, delegate)
    }

    override val output: OutputStream get() = delegate.output
}
