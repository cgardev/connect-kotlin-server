package io.github.cgardev.library.connect.netty

import io.github.cgardev.library.connect.web.ConnectHttpHandler
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http.HttpMessage
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpServerUpgradeHandler
import io.netty.handler.codec.http2.CleartextHttp2ServerUpgradeHandler
import io.netty.handler.codec.http2.Http2CodecUtil
import io.netty.handler.codec.http2.Http2FrameCodecBuilder
import io.netty.handler.codec.http2.Http2MultiplexHandler
import io.netty.handler.codec.http2.Http2ServerUpgradeCodec
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.AsciiString
import io.netty.util.ReferenceCountUtil
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Builds the per-connection Netty pipeline according to which protocols are
 * enabled:
 *  - HTTP/1.1 only ([http1] only): plain HTTP/1.1.
 *  - HTTP/1.1 + HTTP/2 (both): HTTP/2 cleartext via the `Upgrade` handshake and
 *    the HTTP/2 connection preface, falling back to HTTP/1.1.
 *  - HTTP/2 only ([http2] only): HTTP/2 cleartext via the connection preface
 *    (prior knowledge); plain HTTP/1.1 connections are rejected.
 * Either way every request reaches the shared [ConnectChannelHandler] as a
 * `FullHttpRequest`, so the Connect dispatch logic is identical.
 */
class ConnectChannelInitializer(
    private val handler: ConnectHttpHandler,
    private val executor: Executor,
    private val maxContentLength: Int,
    private val http1: Boolean,
    private val http2: Boolean,
    private val idleTimeoutMillis: Long,
) : ChannelInitializer<SocketChannel>() {

    init {
        require(http1 || http2) { "At least one of HTTP/1.1 or HTTP/2 must be enabled" }
    }

    override fun initChannel(ch: SocketChannel) {
        // Drop connections that go idle (no reads and no writes) for too long, so
        // a slow-loris client cannot pin a connection open indefinitely without
        // ever completing a request.
        if (idleTimeoutMillis > 0) {
            ch.pipeline().addLast(IdleStateHandler(0, 0, idleTimeoutMillis, TimeUnit.MILLISECONDS))
            ch.pipeline().addLast(CloseOnIdleHandler())
        }
        when {
            http1 && !http2 -> {
                ch.pipeline().addLast(HttpServerCodec())
                ch.pipeline().addLast(HttpObjectAggregator(maxContentLength))
                ch.pipeline().addLast(connectHandler())
            }
            http1 && http2 -> {
                val sourceCodec = HttpServerCodec()
                val upgradeHandler = HttpServerUpgradeHandler(sourceCodec) { protocol ->
                    if (AsciiString.contentEquals(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME, protocol)) {
                        Http2ServerUpgradeCodec(Http2FrameCodecBuilder.forServer().build(), http2Multiplex())
                    } else {
                        null
                    }
                }
                ch.pipeline().addLast(CleartextHttp2ServerUpgradeHandler(sourceCodec, upgradeHandler, priorKnowledgeHttp2()))
                ch.pipeline().addLast(Http1FallbackHandler())
            }
            else -> {
                // HTTP/2 only: expect the connection preface directly (prior knowledge).
                ch.pipeline().addLast(Http2FrameCodecBuilder.forServer().build())
                ch.pipeline().addLast(http2Multiplex())
            }
        }
    }

    private fun connectHandler() = ConnectChannelHandler(handler, executor)

    /** Closes a connection once [IdleStateHandler] reports it has gone idle. */
    private class CloseOnIdleHandler : io.netty.channel.ChannelInboundHandlerAdapter() {
        override fun userEventTriggered(ctx: ChannelHandlerContext, event: Any) {
            if (event is IdleStateEvent) {
                ctx.close()
            } else {
                ctx.fireUserEventTriggered(event)
            }
        }
    }

    /** Pipeline applied to each inbound HTTP/2 stream so it surfaces as a FullHttpRequest. */
    private fun http2StreamInitializer() = object : ChannelInitializer<Channel>() {
        override fun initChannel(streamChannel: Channel) {
            streamChannel.pipeline().addLast(Http2StreamFrameToHttpObjectCodec(true))
            streamChannel.pipeline().addLast(HttpObjectAggregator(maxContentLength))
            streamChannel.pipeline().addLast(connectHandler())
        }
    }

    private fun http2Multiplex() = Http2MultiplexHandler(http2StreamInitializer())

    /** Installed when an HTTP/2 connection preface is seen directly (prior knowledge). */
    private fun priorKnowledgeHttp2() = object : ChannelInitializer<Channel>() {
        override fun initChannel(connectionChannel: Channel) {
            connectionChannel.pipeline().addLast(Http2FrameCodecBuilder.forServer().build())
            connectionChannel.pipeline().addLast(http2Multiplex())
        }
    }

    /**
     * Reached only when the connection stays HTTP/1.1 (no upgrade, no preface):
     * completes the HTTP/1.1 pipeline and replays the first message through it.
     */
    private inner class Http1FallbackHandler : SimpleChannelInboundHandler<HttpMessage>() {
        override fun channelRead0(ctx: ChannelHandlerContext, message: HttpMessage) {
            val pipeline = ctx.pipeline()
            val name = pipeline.context(this).name()
            pipeline.addAfter(name, null, connectHandler())
            pipeline.replace(this, null, HttpObjectAggregator(maxContentLength))
            ctx.fireChannelRead(ReferenceCountUtil.retain(message))
        }
    }
}
