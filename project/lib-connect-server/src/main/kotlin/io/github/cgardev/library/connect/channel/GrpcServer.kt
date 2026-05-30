package io.github.cgardev.library.connect.channel

import io.grpc.BindableService
import io.grpc.Server
import io.grpc.ServerInterceptor
import io.grpc.ServerInterceptors
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A native gRPC server (HTTP/2 cleartext) that exposes the same
 * [BindableService]s over the classic gRPC protocol, for server-to-server
 * callers. It runs on its own port, independent of the Connect HTTP server, and
 * applies the same [ServerInterceptor] pipeline.
 */
class GrpcServer(
    private val services: List<BindableService>,
    private val interceptors: List<ServerInterceptor>,
    private val host: String,
    private val port: Int,
    private val shutdownGraceMillis: Long,
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(GrpcServer::class.java)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    @Volatile private var server: Server? = null

    @Volatile var boundPort: Int = -1
        private set

    fun start() {
        val builder = NettyServerBuilder.forAddress(InetSocketAddress(host, port)).executor(executor)
        for (service in services) {
            val definition = service.bindService()
            builder.addService(
                if (interceptors.isEmpty()) definition else ServerInterceptors.interceptForward(definition, interceptors),
            )
        }
        val started = builder.build().start()
        server = started
        boundPort = started.port
        log.info("gRPC server listening on {}:{} (HTTP/2 cleartext)", host, boundPort)
    }

    override fun close() {
        val running = server ?: return
        running.shutdown()
        runCatching { running.awaitTermination(shutdownGraceMillis, TimeUnit.MILLISECONDS) }
        running.shutdownNow()
        executor.shutdownNow()
        log.info("gRPC server stopped")
    }
}
