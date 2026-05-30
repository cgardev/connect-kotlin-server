package io.github.cgardev.library.connect.config

/**
 * Plain configuration for the Connect server layer. Holds no framework
 * annotations so the library stays free of any dependency-injection container;
 * a host application maps its own configuration onto this type.
 */
data class ConnectServerConfig(
    /** Address the embedded servers bind to. */
    val host: String = "0.0.0.0",
    /** Port the Connect/gRPC-Web HTTP server binds to; `0` selects an ephemeral port. */
    val port: Int = 8080,
    /**
     * Serve HTTP/1.1 on [port]. The Connect HTTP server runs whenever HTTP/1.1
     * and/or HTTP/2 is enabled.
     */
    val http1Enabled: Boolean = true,
    /**
     * Serve HTTP/2 cleartext (h2c) on [port]. Combined with [http1Enabled] the
     * port negotiates between the two (via the `Upgrade` handshake and the HTTP/2
     * preface); on its own it serves HTTP/2 only (prior-knowledge h2c).
     */
    val http2Enabled: Boolean = false,
    /**
     * Start a native gRPC server (HTTP/2 cleartext) on [grpcPort], exposing the
     * same services over the classic gRPC protocol for server-to-server callers.
     */
    val grpcEnabled: Boolean = false,
    /** Port the native gRPC server binds to (when [grpcEnabled]); `0` is ephemeral. */
    val grpcPort: Int = 9090,
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
    /** Maximum accepted request size after decompression, in bytes. Also bounds each envelope frame. */
    val readMaxBytes: Long = 4L * 1024 * 1024,
    /**
     * Close a connection that has been idle (no reads and no writes) for this
     * long, to defend against slow/stalled connections. `0` disables it.
     */
    val idleTimeoutMillis: Long = 60_000,
    /** Grace period awaited when shutting down the in-process gRPC server/channel. */
    val shutdownGraceMillis: Long = 5_000,
    val cors: Cors = Cors(),
) {
    data class Cors(
        val enabled: Boolean = true,
        /** Allowed origins. `*` matches any origin only when [allowCredentials] is false. */
        val allowedOrigins: List<String> = listOf("*"),
        /**
         * Allow credentialed (cookie/Authorization) cross-origin requests. When true, `*`
         * does NOT match arbitrary origins — only origins explicitly listed in
         * [allowedOrigins] are permitted, to avoid reflecting any origin with credentials.
         */
        val allowCredentials: Boolean = false,
        val allowPrivateNetwork: Boolean = false,
        val maxAgeSeconds: Long = 4 * 60 * 60,
    )
}
