package com.metalogenia.connect.server.netty

import com.metalogenia.connect.server.transport.ConnectHttpResponse
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.DefaultHttpHeaders
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.LastHttpContent
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Adapts [ConnectHttpResponse] onto a Netty channel. Body bytes accumulate in a
 * buffer; an explicit [OutputStream.flush] before completion switches the
 * response to chunked transfer (used for server streaming), while a single
 * write completed by [finish] is sent as one length-delimited response (used
 * for unary). All channel writes are thread-safe: Netty schedules them on the
 * event loop regardless of the calling (worker) thread.
 */
class NettyConnectResponse(
    private val ctx: ChannelHandlerContext,
    private val keepAlive: Boolean,
) : ConnectHttpResponse {

    private var status: HttpResponseStatus = HttpResponseStatus.OK
    private val headers = DefaultHttpHeaders()
    private val buffer = ByteArrayOutputStream()

    @Volatile private var committed = false
    private var finished = false

    override val isCommitted: Boolean get() = committed

    override fun setStatus(code: Int) {
        check(!committed) { "response already committed" }
        status = HttpResponseStatus.valueOf(code)
    }

    override fun setHeader(name: String, value: String) {
        check(!committed) { "response already committed" }
        headers.set(name, value)
    }

    override fun addHeader(name: String, value: String) {
        check(!committed) { "response already committed" }
        headers.add(name, value)
    }

    override fun reset() {
        check(!committed) { "response already committed" }
        status = HttpResponseStatus.OK
        headers.clear()
        buffer.reset()
    }

    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = buffer.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = buffer.write(b, off, len)
        override fun flush() {
            commitChunked()
            sendBufferedChunk()
        }
    }

    /** Writes whatever is buffered and terminates the response. Idempotent. */
    fun finish() {
        if (finished) return
        finished = true
        if (committed) {
            sendBufferedChunk()
            closeOrKeep(ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT))
        } else {
            val content = Unpooled.wrappedBuffer(buffer.toByteArray())
            val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content)
            response.headers().set(headers)
            HttpUtil.setContentLength(response, content.readableBytes().toLong())
            response.headers().set(HttpHeaderNames.CONNECTION, connectionHeader())
            closeOrKeep(ctx.writeAndFlush(response))
        }
    }

    private fun commitChunked() {
        if (committed) return
        val response = DefaultHttpResponse(HttpVersion.HTTP_1_1, status)
        response.headers().set(headers)
        HttpUtil.setTransferEncodingChunked(response, true)
        response.headers().set(HttpHeaderNames.CONNECTION, connectionHeader())
        ctx.writeAndFlush(response)
        committed = true
    }

    private fun sendBufferedChunk() {
        if (buffer.size() == 0) return
        val data = Unpooled.wrappedBuffer(buffer.toByteArray())
        buffer.reset()
        ctx.writeAndFlush(DefaultHttpContent(data))
    }

    private fun connectionHeader() =
        if (keepAlive) HttpHeaderValues.KEEP_ALIVE else HttpHeaderValues.CLOSE

    private fun closeOrKeep(future: io.netty.channel.ChannelFuture) {
        if (!keepAlive) future.addListener(ChannelFutureListener.CLOSE)
    }
}
