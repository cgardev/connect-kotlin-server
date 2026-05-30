package io.github.cgardev.library.connect.netty

import io.github.cgardev.library.connect.transport.ConnectHttpRequest
import io.netty.buffer.ByteBufUtil
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.QueryStringDecoder

/**
 * Adapts a Netty [FullHttpRequest] to [ConnectHttpRequest]. The body is copied
 * eagerly at construction time (on the event-loop thread) so the request can be
 * handed to a worker thread after Netty releases the original buffer.
 */
class NettyConnectRequest(request: FullHttpRequest) : ConnectHttpRequest {

    private val decoder = QueryStringDecoder(request.uri())
    private val nettyHeaders = request.headers()
    private val params: Map<String, List<String>> = decoder.parameters()
    private val bodyBytes: ByteArray = ByteBufUtil.getBytes(request.content())

    override val method: String = request.method().name()
    override val path: String = decoder.path().removePrefix("/")
    override val contentType: String? get() = nettyHeaders.get(HttpHeaderNames.CONTENT_TYPE)

    override fun header(name: String): String? = nettyHeaders.get(name)
    override fun headers(name: String): List<String> = nettyHeaders.getAll(name)
    override val headerNames: Collection<String> get() = nettyHeaders.names()
    override fun queryParam(name: String): String? = params[name]?.firstOrNull()
    override fun body(): ByteArray = bodyBytes
}
