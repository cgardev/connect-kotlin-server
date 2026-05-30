package io.github.cgardev.library.connect.transport

import java.io.OutputStream

/**
 * A transport-neutral view of an inbound HTTP request. Decouples the dispatcher
 * from any specific server (Netty, servlet, …) so the Connect logic can run on
 * any HTTP stack.
 */
interface ConnectHttpRequest {
    val method: String

    /** Path without the query string and without the leading slash (e.g. `pkg.Service/Method`). */
    val path: String

    val contentType: String?

    fun header(name: String): String?

    fun headers(name: String): List<String>

    val headerNames: Collection<String>

    fun queryParam(name: String): String?

    /** The fully-read request body. Implementations enforce their own size bound. */
    fun body(): ByteArray
}

/**
 * A transport-neutral view of the outbound HTTP response. The [output] stream
 * carries the body: the first byte written or [OutputStream.flush] commits the
 * status line and headers. Calling `flush()` between writes signals a streamed
 * (chunked) response; a single write followed by completion yields a buffered
 * response with a content length.
 */
interface ConnectHttpResponse {
    val isCommitted: Boolean

    fun setStatus(code: Int)

    fun setHeader(name: String, value: String)

    fun addHeader(name: String, value: String)

    /** Discards any buffered status, headers and body. Illegal once committed. */
    fun reset()

    val output: OutputStream
}
