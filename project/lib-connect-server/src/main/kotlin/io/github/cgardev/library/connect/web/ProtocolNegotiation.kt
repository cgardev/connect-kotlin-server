package io.github.cgardev.library.connect.web

/** The wire protocol a request uses, derived from its `Content-Type`. */
enum class Protocol {
    /** Connect unary: a single, non-enveloped message body. */
    CONNECT_UNARY,

    /** Connect streaming: enveloped messages plus a JSON end-of-stream envelope. */
    CONNECT_STREAM,

    /** gRPC-Web: enveloped messages plus a trailer frame. */
    GRPC_WEB,
}

/** The protocol and codec resolved from a `Content-Type`. */
data class ContentNegotiation(
    val protocol: Protocol,
    val codecName: String,
    /** The content-type to echo on the response (without charset). */
    val responseContentType: String,
)

/** Header and parameter names used across the Connect family of protocols. */
object ConnectHeaders {
    const val CONTENT_TYPE = "Content-Type"
    const val CONTENT_ENCODING = "Content-Encoding"
    const val ACCEPT_ENCODING = "Accept-Encoding"
    const val PROTOCOL_VERSION = "Connect-Protocol-Version"
    const val TIMEOUT_MS = "Connect-Timeout-Ms"
    const val CONNECT_CONTENT_ENCODING = "Connect-Content-Encoding"
    const val CONNECT_ACCEPT_ENCODING = "Connect-Accept-Encoding"
    const val GRPC_ENCODING = "Grpc-Encoding"
    const val GRPC_ACCEPT_ENCODING = "Grpc-Accept-Encoding"
    const val GRPC_STATUS = "grpc-status"
    const val GRPC_MESSAGE = "grpc-message"
    const val GRPC_STATUS_DETAILS_BIN = "grpc-status-details-bin"
    const val TRAILER_PREFIX = "Trailer-"

    const val PROTOCOL_VERSION_VALUE = "1"
}

/** Parses the `Content-Type` into a [ContentNegotiation], or `null` if unsupported. */
object ProtocolNegotiation {

    fun fromContentType(rawContentType: String?): ContentNegotiation? {
        if (rawContentType.isNullOrBlank()) return null
        val type = rawContentType.substringBefore(';').trim().lowercase()
        return when (type) {
            "application/proto", "application/x-protobuf", "application/protobuf" ->
                ContentNegotiation(Protocol.CONNECT_UNARY, "proto", "application/proto")
            "application/json" ->
                ContentNegotiation(Protocol.CONNECT_UNARY, "json", "application/json")
            "application/connect+proto" ->
                ContentNegotiation(Protocol.CONNECT_STREAM, "proto", "application/connect+proto")
            "application/connect+json" ->
                ContentNegotiation(Protocol.CONNECT_STREAM, "json", "application/connect+json")
            "application/grpc-web", "application/grpc-web+proto" ->
                ContentNegotiation(Protocol.GRPC_WEB, "proto", "application/grpc-web+proto")
            "application/grpc-web+json" ->
                ContentNegotiation(Protocol.GRPC_WEB, "json", "application/grpc-web+json")
            else -> null
        }
    }
}
