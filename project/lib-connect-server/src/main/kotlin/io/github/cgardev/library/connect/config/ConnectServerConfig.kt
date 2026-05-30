package io.github.cgardev.library.connect.config

/**
 * Plain configuration for the Connect server layer. Holds no framework
 * annotations so the library stays free of any dependency-injection container;
 * a host application maps its own configuration onto this type.
 */
data class ConnectServerConfig(
    /** Address the embedded Netty server binds to. */
    val host: String = "0.0.0.0",
    /** Port the embedded Netty server binds to; `0` selects an ephemeral port. */
    val port: Int = 8080,
    /**
     * Base path the dispatcher serves under. RPCs live at
     * `<basePath><package>.<Service>/<Method>`; the default root keeps paths
     * equal to the gRPC full method name.
     */
    val basePath: String = "/",
    /** Require `Connect-Protocol-Version: 1` on Connect unary requests when true. */
    val requireProtocolVersion: Boolean = false,
    /** Allow idempotent (`NO_SIDE_EFFECTS`) unary methods to be invoked via HTTP GET. */
    val getEnabled: Boolean = true,
    /** Only compress responses whose serialized size reaches this threshold. */
    val compressMinBytes: Int = 1024,
    /** Maximum accepted (decompressed) request size, in bytes. */
    val readMaxBytes: Long = 4L * 1024 * 1024,
    /** Grace period awaited when shutting down the in-process gRPC server/channel. */
    val shutdownGraceMillis: Long = 5_000,
    val cors: Cors = Cors(),
) {
    data class Cors(
        val enabled: Boolean = true,
        /** Allowed origins; `*` echoes the request origin (required with credentials). */
        val allowedOrigins: List<String> = listOf("*"),
        val allowCredentials: Boolean = true,
        val allowPrivateNetwork: Boolean = true,
        val maxAgeSeconds: Long = 4 * 60 * 60,
    )
}
