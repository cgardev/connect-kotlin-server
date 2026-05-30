package io.github.cgardev.library.connect.spring

import io.github.cgardev.library.connect.config.ConnectServerConfig
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Spring-bound configuration for the Connect server, exposed under the
 * `connect.server` namespace. Kept separate from the core's plain
 * [ConnectServerConfig] so the library itself stays free of any Spring types.
 */
@ConfigurationProperties(prefix = "connect.server")
data class ConnectServerProperties(
    /** Master switch for the auto-configuration. */
    val enabled: Boolean = true,
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    /** Serve HTTP/1.1 on [port]. */
    val http1Enabled: Boolean = true,
    /** Serve HTTP/2 cleartext (h2c) on [port]; on its own serves HTTP/2 only. */
    val http2Enabled: Boolean = false,
    /** Start a native gRPC server (h2c) on [grpcPort] for server-to-server callers. */
    val grpcEnabled: Boolean = false,
    /** Port the native gRPC server binds to when [grpcEnabled]; `0` is ephemeral. */
    val grpcPort: Int = 9090,
    val basePath: String = "/",
    val requireProtocolVersion: Boolean = false,
    val getEnabled: Boolean = true,
    val compressMinBytes: Int = 1024,
    val readMaxBytes: Long = 4L * 1024 * 1024,
    val shutdownGraceMillis: Long = 5_000,
    val cors: Cors = Cors(),
) {
    data class Cors(
        val enabled: Boolean = true,
        val allowedOrigins: List<String> = listOf("*"),
        val allowCredentials: Boolean = true,
        val allowPrivateNetwork: Boolean = true,
        val maxAgeSeconds: Long = 4 * 60 * 60,
    )

    /** Maps these Spring properties onto the core's plain configuration type. */
    fun toConfig(): ConnectServerConfig = ConnectServerConfig(
        host = host,
        port = port,
        http1Enabled = http1Enabled,
        http2Enabled = http2Enabled,
        grpcEnabled = grpcEnabled,
        grpcPort = grpcPort,
        basePath = basePath,
        requireProtocolVersion = requireProtocolVersion,
        getEnabled = getEnabled,
        compressMinBytes = compressMinBytes,
        readMaxBytes = readMaxBytes,
        shutdownGraceMillis = shutdownGraceMillis,
        cors = ConnectServerConfig.Cors(
            enabled = cors.enabled,
            allowedOrigins = cors.allowedOrigins,
            allowCredentials = cors.allowCredentials,
            allowPrivateNetwork = cors.allowPrivateNetwork,
            maxAgeSeconds = cors.maxAgeSeconds,
        ),
    )
}
