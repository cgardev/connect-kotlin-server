package io.github.cgardev.library.connect

import io.github.cgardev.library.connect.channel.GrpcServer
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

    val registry: ConnectMethodRegistry = ConnectMethodRegistry(services)

    private val httpEnabled = config.http1Enabled || config.http2Enabled

    // The in-process channel + Netty HTTP server only exist when an HTTP protocol
    // is enabled; the dispatcher invokes services through the in-process channel.
    private val channel: InProcessGrpcChannel? =
        if (httpEnabled) InProcessGrpcChannel(services, interceptors, config.shutdownGraceMillis) else null

    private val nettyServer: ConnectNettyServer? = channel?.let { inProcess ->
        val invoker = InProcessInvoker { inProcess.channel }
        val codecs = CodecRegistry(ProtoCodec, JsonCodec(registry.typeRegistry))
        val dispatcher = ConnectDispatcher(registry, invoker, codecs, CompressionRegistry(), ConnectWire(registry.typeRegistry), config)
        val httpHandler = ConnectHttpHandler(dispatcher, ConnectCors(config.cors))
        ConnectNettyServer(
            handler = httpHandler,
            host = config.host,
            port = config.port,
            maxContentLength = config.readMaxBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            shutdownGraceMillis = config.shutdownGraceMillis,
            http1 = config.http1Enabled,
            http2 = config.http2Enabled,
            idleTimeoutMillis = config.idleTimeoutMillis,
        )
    }

    // Optional native gRPC server, for server-to-server callers on a separate port.
    private val grpcServer: GrpcServer? =
        if (config.grpcEnabled) {
            GrpcServer(services, interceptors, config.host, config.grpcPort, config.shutdownGraceMillis)
        } else {
            null
        }

    /** The Connect/gRPC-Web HTTP port; valid after [start]. Throws if no HTTP protocol is enabled. */
    val boundPort: Int
        get() = (nettyServer ?: error("HTTP server is disabled (enable http1Enabled and/or http2Enabled)")).boundPort

    /** The native gRPC port, or null when the gRPC server is disabled; valid after [start]. */
    val grpcBoundPort: Int? get() = grpcServer?.boundPort

    /** Starts the enabled transports: the in-process channel + Netty HTTP server, and/or the gRPC server. */
    fun start() {
        channel?.start()
        nettyServer?.start()
        grpcServer?.start()
    }

    override fun close() {
        runCatching { grpcServer?.close() }
        runCatching { nettyServer?.close() }
        channel?.close()
    }
}
