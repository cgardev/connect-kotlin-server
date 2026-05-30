package io.github.cgardev.library.connect.channel

import io.grpc.BindableService
import io.grpc.Channel
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.ServerInterceptor
import io.grpc.ServerInterceptors
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Hosts the discovered gRPC services on an in-process server and exposes a
 * shared channel to invoke them without crossing the network. Registering the
 * services here (rather than calling their handlers directly) preserves the
 * full [ServerInterceptor] pipeline — exactly the same path a real gRPC client
 * would take. A plain [AutoCloseable] with no framework lifecycle coupling.
 */
class InProcessGrpcChannel(
    private val services: List<BindableService>,
    private val interceptors: List<ServerInterceptor>,
    private val shutdownGraceMillis: Long,
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(InProcessGrpcChannel::class.java)
    private val serverName = "connect-grpc-inprocess-" + UUID.randomUUID()
    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    @Volatile private var server: Server? = null
    @Volatile private var managedChannel: ManagedChannel? = null
    @Volatile private var running = false

    /** The shared channel used by the dispatcher to issue in-process calls. */
    val channel: Channel
        get() = managedChannel ?: error("InProcessGrpcChannel is not running; call start() first")

    val isRunning: Boolean get() = running

    @Synchronized
    fun start() {
        if (running) return
        val serverBuilder = InProcessServerBuilder.forName(serverName)
            .executor(executor)
        for (service in services) {
            val definition = service.bindService()
            val finalDefinition =
                if (interceptors.isEmpty()) definition
                else ServerInterceptors.interceptForward(definition, interceptors)
            serverBuilder.addService(finalDefinition)
        }
        server = serverBuilder.build().start()
        managedChannel = InProcessChannelBuilder.forName(serverName)
            .directExecutor()
            .build()
        running = true
        log.info("In-process gRPC channel started ({} service(s))", services.size)
    }

    @Synchronized
    override fun close() {
        if (!running) return
        running = false
        managedChannel?.shutdown()
        server?.shutdown()
        runCatching {
            managedChannel?.awaitTermination(shutdownGraceMillis, TimeUnit.MILLISECONDS)
            server?.awaitTermination(shutdownGraceMillis, TimeUnit.MILLISECONDS)
        }
        managedChannel?.shutdownNow()
        server?.shutdownNow()
        executor.shutdownNow()
        log.info("In-process gRPC channel stopped")
    }
}
