package io.github.cgardev.library.connect.netty

import io.github.cgardev.library.connect.web.ConnectHttpHandler
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpUtil
import org.slf4j.LoggerFactory
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference

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

        // If the client disconnects while the (blocking) in-process gRPC call is
        // still running, interrupt the worker thread. The gRPC blocking stubs
        // cancel the underlying call on interruption, so the in-process call is
        // cancelled and the worker thread is freed instead of being left blocked.
        val workerThread = AtomicReference<Thread>()
        val closeFuture = ctx.channel().closeFuture()
        val cancelOnClose = ChannelFutureListener { workerThread.getAndSet(null)?.interrupt() }
        closeFuture.addListener(cancelOnClose)

        try {
            executor.execute {
                workerThread.set(Thread.currentThread())
                // The listener may have already fired (the client disconnected before
                // this task started); if the channel is gone, self-interrupt so no
                // cancellation is lost in the registration window.
                if (!ctx.channel().isOpen) Thread.currentThread().interrupt()
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
                    // The call is done: stop treating a close as a cancellation (a
                    // non-keep-alive response closes the channel itself) and detach the
                    // listener so it does not accumulate across keep-alive requests.
                    workerThread.set(null)
                    closeFuture.removeListener(cancelOnClose)
                    response.finish()
                }
            }
        } catch (e: RejectedExecutionException) {
            // The dispatch executor is shutting down: answer with 503 instead of an
            // abrupt connection close, and detach the listener immediately.
            log.warn("Rejected dispatch for {} {} during shutdown", request.method, request.path)
            closeFuture.removeListener(cancelOnClose)
            NettyConnectResponse(ctx, keepAlive).apply {
                setStatus(503)
                finish()
            }
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.debug("Channel error", cause)
        ctx.close()
    }
}
