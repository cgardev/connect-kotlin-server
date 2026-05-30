package io.github.cgardev.library.connect.spring

import io.github.cgardev.library.connect.ConnectServer
import io.grpc.BindableService
import io.grpc.ServerInterceptor
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * Auto-configuration that binds the Connect server library to Spring: it collects
 * every [BindableService] (and [ServerInterceptor]) bean, builds a [ConnectServer]
 * from the bound [ConnectServerProperties], and manages it through a
 * [ConnectServerLifecycle]. Importing this starter is all an application needs —
 * declaring the gRPC service beans is enough to serve them over Connect.
 */
@AutoConfiguration
@ConditionalOnClass(BindableService::class)
@ConditionalOnProperty(prefix = "connect.server", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ConnectServerProperties::class)
class ConnectServerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun connectServer(
        services: ObjectProvider<BindableService>,
        interceptors: ObjectProvider<ServerInterceptor>,
        properties: ConnectServerProperties,
    ): ConnectServer = ConnectServer(
        services = services.orderedStream().toList(),
        interceptors = interceptors.orderedStream().toList(),
        config = properties.toConfig(),
    )

    @Bean
    @ConditionalOnMissingBean
    fun connectServerLifecycle(connectServer: ConnectServer): ConnectServerLifecycle =
        ConnectServerLifecycle(connectServer)
}
