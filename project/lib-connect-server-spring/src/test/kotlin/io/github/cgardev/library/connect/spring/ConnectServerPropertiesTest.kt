package io.github.cgardev.library.connect.spring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConnectServerPropertiesTest {

    @Test
    fun `maps every property onto the core configuration`() {
        val properties = ConnectServerProperties(
            host = "127.0.0.1",
            port = 9090,
            basePath = "/rpc/",
            requireProtocolVersion = true,
            getEnabled = false,
            compressMinBytes = 2048,
            readMaxBytes = 1234,
            shutdownGraceMillis = 7_000,
            cors = ConnectServerProperties.Cors(allowedOrigins = listOf("https://example.com")),
        )

        val config = properties.toConfig()

        assertEquals("127.0.0.1", config.host)
        assertEquals(9090, config.port)
        assertEquals("/rpc/", config.basePath)
        assertEquals(true, config.requireProtocolVersion)
        assertEquals(false, config.getEnabled)
        assertEquals(2048, config.compressMinBytes)
        assertEquals(1234, config.readMaxBytes)
        assertEquals(7_000, config.shutdownGraceMillis)
        assertEquals(listOf("https://example.com"), config.cors.allowedOrigins)
    }
}
