package io.github.cgardev.library.connect

import io.github.cgardev.library.connect.channel.InProcessGrpcChannel
import io.github.cgardev.library.connect.codec.CodecRegistry
import io.github.cgardev.library.connect.codec.CompressionRegistry
import io.github.cgardev.library.connect.codec.JsonCodec
import io.github.cgardev.library.connect.codec.ProtoCodec
import io.github.cgardev.library.connect.config.ConnectServerConfig
import io.github.cgardev.library.connect.invoke.InProcessInvoker
import io.github.cgardev.library.connect.netty.ConnectNettyServer
import io.github.cgardev.library.connect.registry.ConnectMethodRegistry
import io.github.cgardev.library.connect.web.ConnectCors
import io.github.cgardev.library.connect.web.ConnectDispatcher
import io.github.cgardev.library.connect.web.ConnectHttpHandler
import io.github.cgardev.library.connect.web.ConnectWire
import io.grpc.BindableService
import io.grpc.ServerInterceptor

/**
 * Framework-agnostic entry point to the Connect server. Given a set of gRPC
 * [BindableService]s (and optional [ServerInterceptor]s), it hosts them on an
 * in-process gRPC channel (preserving the interceptor pipeline) and serves them
 * over an embedded Netty HTTP server speaking the Connect, Connect-streaming and
 * gRPC-Web protocols. Call [start] before serving and [close] on shutdown.
 *
 * This class — and the entire library — has no dependency on Spring or any other
 * application container.
 */
class ConnectServer(
    services: List<BindableService>,
    interceptors: List<ServerInterceptor> = emptyList(),
    val config: ConnectServerConfig = ConnectServerConfig(),
) : AutoCloseable {

    private val channel = InProcessGrpcChannel(services, interceptors, config.shutdownGraceMillis)
    val registry: ConnectMethodRegistry = ConnectMethodRegistry(services)

    private val httpHandler: ConnectHttpHandler = run {
        val invoker = InProcessInvoker { channel.channel }
        val codecs = CodecRegistry(ProtoCodec, JsonCodec(registry.typeRegistry))
        val dispatcher = ConnectDispatcher(registry, invoker, codecs, CompressionRegistry(), ConnectWire(registry.typeRegistry), config)
        ConnectHttpHandler(dispatcher, ConnectCors(config.cors))
    }

    private val nettyServer = ConnectNettyServer(
        handler = httpHandler,
        host = config.host,
        port = config.port,
        maxContentLength = config.readMaxBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        shutdownGraceMillis = config.shutdownGraceMillis,
    )

    /** The port the server is listening on; valid after [start] (resolves ephemeral port 0). */
    val boundPort: Int get() = nettyServer.boundPort

    /** Starts the in-process gRPC channel and binds the Netty HTTP server. */
    fun start() {
        channel.start()
        nettyServer.start()
    }

    override fun close() {
        runCatching { nettyServer.close() }
        channel.close()
    }
}
