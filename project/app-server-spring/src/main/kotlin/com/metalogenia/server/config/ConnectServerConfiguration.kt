package com.metalogenia.server.config

import com.metalogenia.connect.server.ConnectServer
import com.metalogenia.connect.server.config.ConnectServerConfig
import io.grpc.BindableService
import io.grpc.ServerInterceptor
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The only place Spring meets the (Spring-free) Connect server library. It
 * collects the gRPC service and interceptor beans, builds a [ConnectServer]
 * (which embeds its own Netty HTTP server) and manages its lifecycle.
 */
@Configuration
class ConnectServerConfiguration {

    @Bean(destroyMethod = "close")
    fun connectServer(
        services: ObjectProvider<BindableService>,
        interceptors: ObjectProvider<ServerInterceptor>,
        @Value("\${connect.server.port:8080}") port: Int,
    ): ConnectServer {
        val server = ConnectServer(
            services = services.orderedStream().toList(),
            interceptors = interceptors.orderedStream().toList(),
            config = ConnectServerConfig(port = port),
        )
        server.start()
        return server
    }
}
