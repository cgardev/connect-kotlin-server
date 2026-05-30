package io.github.cgardev.library.connect.netty

import io.github.cgardev.library.connect.web.ConnectHttpHandler
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The embedded Netty server that terminates the Connect protocols. It serves
 * HTTP/1.1 and, when [http2] is enabled, HTTP/2 cleartext (h2c) on the same
 * port. Each request is dispatched on a virtual-thread executor (so the blocking
 * in-process gRPC calls stay off the event loop) and the response is streamed
 * back through the channel.
 */
class ConnectNettyServer(
    private val handler: ConnectHttpHandler,
    private val host: String,
    private val port: Int,
    private val maxContentLength: Int,
    private val shutdownGraceMillis: Long,
    private val http1: Boolean,
    private val http2: Boolean,
    private val idleTimeoutMillis: Long,
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(ConnectNettyServer::class.java)
    private val bossGroup = NioEventLoopGroup(1)
    private val workerGroup = NioEventLoopGroup()
    private val dispatchExecutor = Executors.newVirtualThreadPerTaskExecutor()

    @Volatile private var channel: Channel? = null

    /** The actual bound port (resolves the OS-assigned port when configured with 0). */
    @Volatile var boundPort: Int = -1
        private set

    fun start() {
        val bootstrap = ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(ConnectChannelInitializer(handler, dispatchExecutor, maxContentLength, http1, http2, idleTimeoutMillis))
        val bound = bootstrap.bind(host, port).sync().channel()
        channel = bound
        boundPort = (bound.localAddress() as InetSocketAddress).port
        val protocols = listOfNotNull("HTTP/1.1".takeIf { http1 }, "h2c".takeIf { http2 }).joinToString(" + ")
        log.info("Connect Netty server listening on {}:{} ({})", host, boundPort, protocols)
    }

    override fun close() {
        runCatching { channel?.close()?.sync() }
        val quietMillis = (shutdownGraceMillis / 4).coerceAtLeast(0)
        workerGroup.shutdownGracefully(quietMillis, shutdownGraceMillis, TimeUnit.MILLISECONDS)
        bossGroup.shutdownGracefully(quietMillis, shutdownGraceMillis, TimeUnit.MILLISECONDS)
        dispatchExecutor.shutdownNow()
        log.info("Connect Netty server stopped")
    }
}
