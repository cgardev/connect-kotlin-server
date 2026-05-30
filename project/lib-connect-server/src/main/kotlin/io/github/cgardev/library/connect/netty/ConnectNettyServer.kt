package io.github.cgardev.library.connect.netty

import io.github.cgardev.library.connect.web.ConnectHttpHandler
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The embedded Netty HTTP/1.1 server that terminates the Connect protocols. It
 * aggregates each request, then dispatches it on a virtual-thread executor (so
 * the blocking in-process gRPC calls stay off the event loop) and streams the
 * response back through the channel.
 */
class ConnectNettyServer(
    private val handler: ConnectHttpHandler,
    private val host: String,
    private val port: Int,
    private val maxContentLength: Int,
    private val shutdownGraceMillis: Long,
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
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline().addLast(HttpServerCodec())
                    ch.pipeline().addLast(HttpObjectAggregator(maxContentLength))
                    ch.pipeline().addLast(ConnectChannelHandler(handler, dispatchExecutor))
                }
            })
        val bound = bootstrap.bind(host, port).sync().channel()
        channel = bound
        boundPort = (bound.localAddress() as InetSocketAddress).port
        log.info("Connect Netty server listening on {}:{}", host, boundPort)
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
