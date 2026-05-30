package io.github.cgardev.library.connect.spring

import io.github.cgardev.library.connect.ConnectServer
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Binds the (Spring-free) [ConnectServer] to Spring's [SmartLifecycle], so the
 * embedded Netty server starts and stops with the application context. Modelled
 * on the reference project's `ManagedGrpcServerLifecycle`: it auto-starts, runs
 * in a late phase (after the rest of the context is ready) and shuts the server
 * down gracefully on context close.
 */
class ConnectServerLifecycle(private val connectServer: ConnectServer) : SmartLifecycle {

    private val log = LoggerFactory.getLogger(ConnectServerLifecycle::class.java)
    private val running = AtomicBoolean(false)

    override fun start() {
        if (running.compareAndSet(false, true)) {
            try {
                connectServer.start()
                log.info("Connect server started on port {}", connectServer.boundPort)
            } catch (ex: Exception) {
                running.set(false)
                throw IllegalStateException("Failed to start the Connect server", ex)
            }
        }
    }

    override fun stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping Connect server...")
            connectServer.close()
        }
    }

    override fun stop(callback: Runnable) {
        stop()
        callback.run()
    }

    override fun isRunning(): Boolean = running.get()

    override fun isAutoStartup(): Boolean = true

    /** Start last and stop first, so traffic is only served once the context is ready. */
    override fun getPhase(): Int = Int.MAX_VALUE
}
