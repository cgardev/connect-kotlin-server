package com.metalogenia.connect.server.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.protobuf.DynamicMessage
import com.google.protobuf.TypeRegistry
import com.google.protobuf.util.JsonFormat
import com.metalogenia.connect.server.error.ConnectCode
import com.metalogenia.connect.server.error.ConnectErrorDetail
import com.metalogenia.connect.server.error.ConnectException
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Renders Connect wire artifacts: the unary error JSON body, the Connect
 * streaming end-of-stream envelope payload, and the gRPC-Web trailer frame.
 * Verified field-by-field against the connect-go reference implementation.
 */
class ConnectWire(private val typeRegistry: TypeRegistry) {

    private val mapper = ObjectMapper()
    private val base64NoPad = Base64.getEncoder().withoutPadding()
    private val debugPrinter: JsonFormat.Printer =
        JsonFormat.printer().usingTypeRegistry(typeRegistry).omittingInsignificantWhitespace()

    /** The `application/json` body of a Connect unary error response. */
    fun unaryErrorBody(error: ConnectException): ByteArray =
        mapper.writeValueAsBytes(errorNode(error))

    /**
     * The JSON payload of the Connect streaming end-of-stream envelope:
     * `{ "error"?: {...}, "metadata"?: {...} }`. `error` is omitted on success.
     */
    fun endStreamPayload(error: ConnectException?, trailers: Map<String, List<String>>): ByteArray {
        val root = mapper.createObjectNode()
        if (error != null) root.set<ObjectNode>("error", errorNode(error))
        if (trailers.isNotEmpty()) {
            val metadata = root.putObject("metadata")
            for ((name, values) in trailers) {
                val array = metadata.putArray(name)
                values.forEach { array.add(it) }
            }
        }
        return mapper.writeValueAsBytes(root)
    }

    /**
     * The data of a gRPC-Web trailer frame: a raw HTTP/1.1 header block with
     * lowercase names and CRLF line endings.
     */
    fun grpcWebTrailer(
        statusCode: Int,
        message: String,
        statusDetailsBin: ByteArray?,
        extraTrailers: List<Pair<String, String>>,
    ): ByteArray {
        val builder = StringBuilder()
        builder.append(ConnectHeaders.GRPC_STATUS).append(": ").append(statusCode).append("\r\n")
        if (message.isNotEmpty()) {
            builder.append(ConnectHeaders.GRPC_MESSAGE).append(": ").append(grpcPercentEncode(message)).append("\r\n")
        }
        if (statusDetailsBin != null) {
            builder.append(ConnectHeaders.GRPC_STATUS_DETAILS_BIN).append(": ")
                .append(base64NoPad.encodeToString(statusDetailsBin)).append("\r\n")
        }
        for ((name, value) in extraTrailers) {
            builder.append(name).append(": ").append(value).append("\r\n")
        }
        return builder.toString().toByteArray(StandardCharsets.US_ASCII)
    }

    private fun errorNode(error: ConnectException): ObjectNode {
        val node = mapper.createObjectNode()
        node.put("code", error.code.wireName)
        if (error.message.isNotEmpty()) node.put("message", error.message)
        if (error.details.isNotEmpty()) {
            val array = node.putArray("details")
            error.details.forEach { array.add(detailNode(it)) }
        }
        return node
    }

    private fun detailNode(detail: ConnectErrorDetail): ObjectNode {
        val node = mapper.createObjectNode()
        node.put("type", typeNameForUrl(detail.any.typeUrl))
        node.put("value", base64NoPad.encodeToString(detail.any.value.toByteArray()))
        debugJson(detail)?.let { node.set<ObjectNode>("debug", mapper.readTree(it)) }
        return node
    }

    /** Best-effort human-readable rendering of a detail, when its type is known. */
    private fun debugJson(detail: ConnectErrorDetail): String? {
        val typeName = typeNameForUrl(detail.any.typeUrl)
        val descriptor = runCatching { typeRegistry.find(typeName) }.getOrNull() ?: return null
        return runCatching {
            val message = DynamicMessage.parseFrom(descriptor, detail.any.value)
            debugPrinter.print(message)
        }.getOrNull()
    }

    private fun typeNameForUrl(typeUrl: String): String = typeUrl.substringAfterLast('/')

    /** gRPC `grpc-message` percent-encoding: escape bytes < 0x20, > 0x7E and '%'. */
    private fun grpcPercentEncode(message: String): String {
        val bytes = message.toByteArray(StandardCharsets.UTF_8)
        val builder = StringBuilder(bytes.size)
        for (raw in bytes) {
            val value = raw.toInt() and 0xFF
            if (value < 0x20 || value > 0x7E || value == '%'.code) {
                builder.append('%')
                builder.append(HEX[value ushr 4])
                builder.append(HEX[value and 0x0F])
            } else {
                builder.append(value.toChar())
            }
        }
        return builder.toString()
    }

    private companion object {
        private const val HEX = "0123456789ABCDEF"
    }
}
