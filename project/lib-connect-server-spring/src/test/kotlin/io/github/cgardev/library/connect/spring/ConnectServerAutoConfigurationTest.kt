package io.github.cgardev.library.connect.spring

import io.github.cgardev.library.connect.ConnectServer
import io.grpc.BindableService
import io.grpc.ServerServiceDefinition
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class ConnectServerAutoConfigurationTest {

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConnectServerAutoConfiguration::class.java))

    @Test
    fun `auto-configures, registers a lifecycle and starts the server`() {
        runner
            .withBean(DummyService::class.java)
            .withPropertyValues("connect.server.port=0")
            .run { context ->
                assertThat(context).hasSingleBean(ConnectServer::class.java)
                assertThat(context).hasSingleBean(ConnectServerLifecycle::class.java)
                assertThat(context.getBean(ConnectServerLifecycle::class.java).isRunning).isTrue()
                assertThat(context.getBean(ConnectServer::class.java).boundPort).isGreaterThan(0)
            }
    }

    @Test
    fun `backs off when disabled`() {
        runner
            .withBean(DummyService::class.java)
            .withPropertyValues("connect.server.enabled=false")
            .run { context -> assertThat(context).doesNotHaveBean(ConnectServer::class.java) }
    }

    /** A no-method service is enough to exercise the wiring. */
    private class DummyService : BindableService {
        override fun bindService(): ServerServiceDefinition =
            ServerServiceDefinition.builder("dummy.v1.DummyService").build()
    }
}
