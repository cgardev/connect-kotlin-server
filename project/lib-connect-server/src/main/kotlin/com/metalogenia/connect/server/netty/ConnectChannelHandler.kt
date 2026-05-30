package com.metalogenia.connect.server.netty

import com.metalogenia.connect.server.web.ConnectHttpHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpUtil
import org.slf4j.LoggerFactory
import java.util.concurrent.Executor

/**
 * Bridges Netty's HTTP pipeline to the transport-neutral [ConnectHttpHandler].
 * The request is snapshotted on the event-loop thread, then handled on a worker
 * [Executor] so the blocking in-process gRPC calls never run on an event loop.
 */
class ConnectChannelHandler(
    private val handler: ConnectHttpHandler,
    private val executor: Executor,
) : SimpleChannelInboundHandler<FullHttpRequest>() {

    private val log = LoggerFactory.getLogger(ConnectChannelHandler::class.java)

    override fun channelRead0(ctx: ChannelHandlerContext, msg: FullHttpRequest) {
        val keepAlive = HttpUtil.isKeepAlive(msg)
        val request = NettyConnectRequest(msg) // copies headers + body off the pooled buffer
        executor.execute {
            val response = NettyConnectResponse(ctx, keepAlive)
            try {
                handler.handle(request, response)
            } catch (e: Exception) {
                log.error("Unhandled error dispatching {} {}", request.method, request.path, e)
                if (!response.isCommitted) {
                    response.reset()
                    response.setStatus(500)
                }
            } finally {
                response.finish()
            }
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.debug("Channel error", cause)
        ctx.close()
    }
}
