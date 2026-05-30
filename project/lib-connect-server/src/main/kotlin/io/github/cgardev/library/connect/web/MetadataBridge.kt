package io.github.cgardev.library.connect.web

import io.github.cgardev.library.connect.transport.ConnectHttpRequest
import io.grpc.Metadata
import java.util.Base64

/**
 * Translates between HTTP headers and gRPC [Metadata]. Reserved protocol headers
 * (content negotiation, compression, timeout, framing) are never forwarded as
 * call metadata; everything else is passed through so application headers such
 * as `Authorization` reach the gRPC handlers.
 */
object MetadataBridge {

    private val RESERVED_REQUEST_HEADERS = setOf(
        "content-type", "content-length", "content-encoding", "accept-encoding",
        "connect-protocol-version", "connect-timeout-ms",
        "connect-content-encoding", "connect-accept-encoding",
        "grpc-encoding", "grpc-accept-encoding", "grpc-timeout",
        "host", "connection", "keep-alive", "te", "transfer-encoding", "upgrade",
    )

    private val BINARY_SUFFIX = Metadata.BINARY_HEADER_SUFFIX // "-bin"
    private val base64Encoder = Base64.getEncoder().withoutPadding()
    private val base64Decoder = Base64.getDecoder()

    /** Builds gRPC call metadata from the inbound request headers. */
    fun requestToMetadata(request: ConnectHttpRequest): Metadata {
        val metadata = Metadata()
        for (name in request.headerNames) {
            val lower = name.lowercase()
            if (lower in RESERVED_REQUEST_HEADERS) continue
            val values = request.headers(name)
            if (lower.endsWith(BINARY_SUFFIX)) {
                val key = Metadata.Key.of(lower, Metadata.BINARY_BYTE_MARSHALLER)
                for (value in values) metadata.put(key, decodeBinary(value))
            } else {
                val key = Metadata.Key.of(lower, Metadata.ASCII_STRING_MARSHALLER)
                for (value in values) metadata.put(key, value)
            }
        }
        return metadata
    }

    /**
     * Renders response metadata as a list of HTTP header name/value pairs. Binary
     * keys (`-bin`) are base64-encoded without padding; ASCII keys are passed
     * through verbatim. [namePrefix] is used to emit Connect unary trailers as
     * `Trailer-`-prefixed response headers.
     */
    fun metadataToHeaders(metadata: Metadata, namePrefix: String = ""): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (key in metadata.keys()) {
            if (key.endsWith(BINARY_SUFFIX)) {
                val binaryKey = Metadata.Key.of(key, Metadata.BINARY_BYTE_MARSHALLER)
                metadata.getAll(binaryKey)?.forEach { value ->
                    out += (namePrefix + key) to base64Encoder.encodeToString(value)
                }
            } else {
                val asciiKey = Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER)
                metadata.getAll(asciiKey)?.forEach { value ->
                    out += (namePrefix + key) to value
                }
            }
        }
        return out
    }

    private fun decodeBinary(value: String): ByteArray {
        val padded = when (value.length % 4) {
            2 -> "$value=="
            3 -> "$value="
            else -> value
        }
        return base64Decoder.decode(padded)
    }
}
