package io.github.cgardev.library.connect.web

import com.google.protobuf.Message
import io.github.cgardev.library.connect.codec.CodecRegistry
import io.github.cgardev.library.connect.codec.Compression
import io.github.cgardev.library.connect.codec.CompressionRegistry
import io.github.cgardev.library.connect.codec.ConnectCodec
import io.github.cgardev.library.connect.config.ConnectServerConfig
import io.github.cgardev.library.connect.error.ConnectCode
import io.github.cgardev.library.connect.error.ConnectErrorMapper
import io.github.cgardev.library.connect.error.ConnectException
import io.github.cgardev.library.connect.framing.Envelope
import io.github.cgardev.library.connect.invoke.InProcessInvoker
import io.github.cgardev.library.connect.registry.ConnectMethodEntry
import io.github.cgardev.library.connect.registry.ConnectMethodRegistry
import io.github.cgardev.library.connect.transport.ConnectHttpRequest
import io.github.cgardev.library.connect.transport.ConnectHttpResponse
import io.grpc.Metadata
import io.grpc.MethodDescriptor.MethodType
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Terminates the Connect family of protocols and forwards each call to the
 * in-process gRPC pipeline. It resolves the request path to a registered
 * method, negotiates the protocol and codec from the `Content-Type`, decodes
 * the request, invokes the handler and writes the response in the negotiated
 * wire format. Works against the transport-neutral
 * [ConnectHttpRequest]/[ConnectHttpResponse] abstractions.
 */
class ConnectDispatcher(
    private val registry: ConnectMethodRegistry,
    private val invoker: InProcessInvoker,
    private val codecRegistry: CodecRegistry,
    private val compressions: CompressionRegistry,
    private val wire: ConnectWire,
    private val config: ConnectServerConfig,
) {

    private val log = LoggerFactory.getLogger(ConnectDispatcher::class.java)

    /**
     * Whether this request targets an RPC. RPC paths have the shape
     * `<package>.<Service>/<Method>` — a two-segment path whose first segment is
     * a dotted, fully-qualified service name. Everything else is left to the host.
     */
    fun handles(request: ConnectHttpRequest): Boolean {
        val path = request.path
        val slash = path.indexOf('/')
        if (slash <= 0 || slash == path.length - 1) return false
        val service = path.substring(0, slash)
        val method = path.substring(slash + 1)
        return service.contains('.') && !method.contains('/')
    }

    /** Routes a claimed request by HTTP method. */
    fun handle(request: ConnectHttpRequest, response: ConnectHttpResponse) {
        when (request.method.uppercase()) {
            "POST" -> doPost(request, response)
            "GET" -> doGet(request, response)
            else -> writeUnaryError(response, ConnectException(ConnectCode.UNIMPLEMENTED, "unsupported HTTP method"))
        }
    }

    private fun doPost(request: ConnectHttpRequest, response: ConnectHttpResponse) {
        val negotiation = ProtocolNegotiation.fromContentType(request.contentType)
        if (negotiation == null) {
            response.setStatus(HTTP_UNSUPPORTED_MEDIA_TYPE)
            return
        }
        val entry = registry.find(request.path)
        if (entry == null) {
            writeUnaryError(response, ConnectException(ConnectCode.UNIMPLEMENTED, "unknown method"))
            return
        }
        try {
            when (negotiation.protocol) {
                Protocol.CONNECT_UNARY -> handleConnectUnary(request, response, entry, negotiation)
                Protocol.CONNECT_STREAM, Protocol.GRPC_WEB -> handleEnveloped(request, response, entry, negotiation)
            }
        } catch (e: Exception) {
            failBeforeBody(response, negotiation, e)
        }
    }

    private fun doGet(request: ConnectHttpRequest, response: ConnectHttpResponse) {
        if (!config.getEnabled) {
            writeUnaryError(response, ConnectException(ConnectCode.UNIMPLEMENTED, "GET is disabled"))
            return
        }
        val entry = registry.find(request.path)
        if (entry == null || entry.type != MethodType.UNARY || !entry.noSideEffects) {
            writeUnaryError(response, ConnectException(ConnectCode.UNIMPLEMENTED, "method not available via GET"))
            return
        }
        try {
            handleConnectGet(request, response, entry)
        } catch (e: Exception) {
            writeUnaryError(response, ConnectErrorMapper.toConnectException(e))
        }
    }

    private fun handleConnectUnary(
        request: ConnectHttpRequest,
        response: ConnectHttpResponse,
        entry: ConnectMethodEntry,
        negotiation: ContentNegotiation,
    ) {
        if (entry.type != MethodType.UNARY) {
            writeUnaryError(response, ConnectException(ConnectCode.UNIMPLEMENTED, "method requires a streaming protocol"))
            return
        }
        if (config.requireProtocolVersion &&
            request.header(ConnectHeaders.PROTOCOL_VERSION) != ConnectHeaders.PROTOCOL_VERSION_VALUE
        ) {
            writeUnaryError(response, ConnectException(ConnectCode.INVALID_ARGUMENT, "missing Connect-Protocol-Version"))
            return
        }
        val codec = codecRegistry.byName(negotiation.codecName)
            ?: throw ConnectException(ConnectCode.INTERNAL, "no codec for ${negotiation.codecName}")

        var body = readBody(request)
        compressions.resolveOrReject(request.header(ConnectHeaders.CONTENT_ENCODING))?.let { body = it.decompress(body) }
        val message = codec.deserialize(body, entry.requestPrototype)

        invokeUnaryAndWrite(request, response, entry, message, codec, negotiation.responseContentType)
    }

    private fun handleConnectGet(
        request: ConnectHttpRequest,
        response: ConnectHttpResponse,
        entry: ConnectMethodEntry,
    ) {
        if (config.requireProtocolVersion && request.queryParam("connect") != "v${ConnectHeaders.PROTOCOL_VERSION_VALUE}") {
            writeUnaryError(response, ConnectException(ConnectCode.INVALID_ARGUMENT, "missing connect=v1"))
            return
        }
        val encoding = request.queryParam("encoding")
            ?: throw ConnectException(ConnectCode.INVALID_ARGUMENT, "missing encoding parameter")
        val codec = codecRegistry.byName(encoding)
            ?: throw ConnectException(ConnectCode.INVALID_ARGUMENT, "unsupported encoding $encoding")
        val rawMessage = request.queryParam("message")
            ?: throw ConnectException(ConnectCode.INVALID_ARGUMENT, "missing message parameter")

        var body = if (request.queryParam("base64") == "1") {
            decodeBase64Url(rawMessage)
        } else {
            rawMessage.toByteArray(StandardCharsets.UTF_8)
        }
        compressions.resolveOrReject(request.queryParam("compression"))?.let { body = it.decompress(body) }
        val message = codec.deserialize(body, entry.requestPrototype)

        response.addHeader("Vary", ConnectHeaders.ACCEPT_ENCODING)
        invokeUnaryAndWrite(request, response, entry, message, codec, codecContentType(encoding))
    }

    private fun invokeUnaryAndWrite(
        request: ConnectHttpRequest,
        response: ConnectHttpResponse,
        entry: ConnectMethodEntry,
        message: Message,
        codec: ConnectCodec,
        responseContentType: String,
    ) {
        val metadata = MetadataBridge.requestToMetadata(request)
        val result = try {
            invoker.unary(entry, message, metadata, connectTimeoutMillis(request))
        } catch (e: Exception) {
            writeUnaryError(response, ConnectErrorMapper.toConnectException(e))
            return
        }

        var payload = codec.serialize(result.message)
        val compression = negotiateUnaryResponseCompression(request, payload.size)
        if (compression != null) {
            payload = compression.compress(payload)
            response.setHeader(ConnectHeaders.CONTENT_ENCODING, compression.name)
        }
        response.setHeader(ConnectHeaders.ACCEPT_ENCODING, compressions.acceptedEncodings)
        response.setStatus(HTTP_OK)
        response.setHeader("Content-Type", responseContentType)
        result.headers?.let { writeLeadingHeaders(response, it) }
        result.trailers?.let { writeUnaryTrailers(response, it) }
        response.output.write(payload)
    }

    private fun handleEnveloped(
        request: ConnectHttpRequest,
        response: ConnectHttpResponse,
        entry: ConnectMethodEntry,
        negotiation: ContentNegotiation,
    ) {
        val grpcWeb = negotiation.protocol == Protocol.GRPC_WEB
        val codec = codecRegistry.byName(negotiation.codecName)
            ?: throw ConnectException(ConnectCode.INTERNAL, "no codec for ${negotiation.codecName}")

        val requestEncodingHeader =
            if (grpcWeb) ConnectHeaders.GRPC_ENCODING else ConnectHeaders.CONNECT_CONTENT_ENCODING
        val requestCompression = compressions.resolveOrReject(request.header(requestEncodingHeader))
        val message = decodeEnvelopedRequest(request, entry, codec, requestCompression)

        val responseCompression = negotiateStreamResponseCompression(request, grpcWeb)
        response.setStatus(HTTP_OK)
        response.setHeader("Content-Type", negotiation.responseContentType)
        if (responseCompression != null) {
            val header = if (grpcWeb) ConnectHeaders.GRPC_ENCODING else ConnectHeaders.CONNECT_CONTENT_ENCODING
            response.setHeader(header, responseCompression.name)
        }
        val acceptHeader = if (grpcWeb) ConnectHeaders.GRPC_ACCEPT_ENCODING else ConnectHeaders.CONNECT_ACCEPT_ENCODING
        response.setHeader(acceptHeader, compressions.acceptedEncodings)

        val out = response.output
        val metadata = MetadataBridge.requestToMetadata(request)
        val timeout = if (grpcWeb) grpcTimeoutMillis(request) else connectTimeoutMillis(request)

        when (entry.type) {
            MethodType.UNARY -> streamUnary(response, out, entry, message, metadata, timeout, codec, responseCompression, grpcWeb)
            MethodType.SERVER_STREAMING ->
                streamServer(out, entry, message, metadata, timeout, codec, responseCompression, grpcWeb)
            else -> writeEndOfStream(
                out, grpcWeb, ConnectException(ConnectCode.UNIMPLEMENTED, "unsupported method type ${entry.type}"), null,
            )
        }
        out.flush()
    }

    private fun streamUnary(
        response: ConnectHttpResponse,
        out: OutputStream,
        entry: ConnectMethodEntry,
        message: Message,
        metadata: Metadata,
        timeout: Long?,
        codec: ConnectCodec,
        compression: Compression?,
        grpcWeb: Boolean,
    ) {
        try {
            val result = invoker.unary(entry, message, metadata, timeout)
            // Leading metadata travels as response headers (still uncommitted here);
            // trailing metadata rides the end-of-stream frame below.
            result.headers?.let { writeLeadingHeaders(response, it) }
            writeMessageFrame(out, codec.serialize(result.message), compression)
            writeEndOfStream(out, grpcWeb, null, result.trailers)
        } catch (e: Exception) {
            writeEndOfStream(out, grpcWeb, ConnectErrorMapper.toConnectException(e), null)
        }
    }

    private fun streamServer(
        out: OutputStream,
        entry: ConnectMethodEntry,
        message: Message,
        metadata: Metadata,
        timeout: Long?,
        codec: ConnectCodec,
        compression: Compression?,
        grpcWeb: Boolean,
    ) {
        val call = invoker.serverStream(entry, message, metadata, timeout)
        try {
            while (call.messages.hasNext()) {
                writeMessageFrame(out, codec.serialize(call.messages.next()), compression)
                out.flush()
            }
            writeEndOfStream(out, grpcWeb, null, call.trailers())
        } catch (e: Exception) {
            writeEndOfStream(out, grpcWeb, ConnectErrorMapper.toConnectException(e), call.trailers())
        }
    }

    private fun decodeEnvelopedRequest(
        request: ConnectHttpRequest,
        entry: ConnectMethodEntry,
        codec: ConnectCodec,
        compression: Compression?,
    ): Message {
        val frames = Envelope.readAll(ByteArrayInputStream(readBody(request)))
        val dataFrame = frames.firstOrNull {
            it.flags and Envelope.FLAG_GRPC_WEB_TRAILER == 0 && it.flags and Envelope.FLAG_CONNECT_END_STREAM == 0
        } ?: throw ConnectException(ConnectCode.INVALID_ARGUMENT, "missing request message")
        var bytes = dataFrame.data
        if (dataFrame.flags and Envelope.FLAG_COMPRESSED != 0) {
            // A compressed frame with no negotiated decoder is a protocol error on the server side.
            bytes = (compression ?: throw ConnectException(ConnectCode.INTERNAL, "compressed frame without a negotiated encoding"))
                .decompress(bytes)
        }
        return codec.deserialize(bytes, entry.requestPrototype)
    }

    private fun writeMessageFrame(out: OutputStream, payload: ByteArray, compression: Compression?) {
        if (compression != null && payload.size >= config.compressMinBytes) {
            Envelope.writeFrame(out, Envelope.FLAG_COMPRESSED, compression.compress(payload))
        } else {
            Envelope.writeFrame(out, 0, payload)
        }
    }

    private fun writeEndOfStream(out: OutputStream, grpcWeb: Boolean, error: ConnectException?, trailers: Metadata?) {
        if (grpcWeb) {
            val statusCode = error?.code?.number ?: 0
            val statusDetails = error?.takeIf { it.details.isNotEmpty() }?.let { GrpcStatusDetails.toBytes(it) }
            val extra = trailers?.let { trailerHeaders(it) } ?: emptyList()
            Envelope.writeFrame(
                out, Envelope.FLAG_GRPC_WEB_TRAILER,
                wire.grpcWebTrailer(statusCode, error?.message ?: "", statusDetails, extra),
            )
        } else {
            val grouped = trailers?.let { groupedTrailers(it) } ?: emptyMap()
            Envelope.writeFrame(out, Envelope.FLAG_CONNECT_END_STREAM, wire.endStreamPayload(error, grouped))
        }
    }

    private fun writeUnaryError(response: ConnectHttpResponse, error: ConnectException) {
        if (response.isCommitted) {
            log.warn("Cannot write error, response already committed: {}", error.message)
            return
        }
        response.reset()
        response.setStatus(error.code.httpStatus)
        response.setHeader("Content-Type", "application/json")
        response.output.write(wire.unaryErrorBody(error))
    }

    private fun failBeforeBody(response: ConnectHttpResponse, negotiation: ContentNegotiation, e: Exception) {
        val error = ConnectErrorMapper.toConnectException(e)
        if (negotiation.protocol == Protocol.CONNECT_UNARY) {
            writeUnaryError(response, error)
        } else if (!response.isCommitted) {
            response.setStatus(HTTP_OK)
            response.setHeader("Content-Type", negotiation.responseContentType)
            writeEndOfStream(response.output, negotiation.protocol == Protocol.GRPC_WEB, error, null)
            response.output.flush()
        }
    }

    private fun writeLeadingHeaders(response: ConnectHttpResponse, headers: Metadata) {
        MetadataBridge.metadataToHeaders(headers)
            .filterNot { (name, _) -> name.lowercase() in RESERVED_RESPONSE_HEADERS }
            .forEach { (name, value) -> response.addHeader(name, value) }
    }

    private fun writeUnaryTrailers(response: ConnectHttpResponse, trailers: Metadata) {
        MetadataBridge.metadataToHeaders(trailers, ConnectHeaders.TRAILER_PREFIX)
            .forEach { (name, value) -> response.addHeader(name, value) }
    }

    private fun trailerHeaders(trailers: Metadata): List<Pair<String, String>> =
        MetadataBridge.metadataToHeaders(trailers)
            .filterNot { (name, _) -> name.lowercase() in RESERVED_RESPONSE_HEADERS }

    private fun groupedTrailers(trailers: Metadata): Map<String, List<String>> =
        trailerHeaders(trailers).groupBy({ it.first }, { it.second })

    private fun readBody(request: ConnectHttpRequest): ByteArray {
        val body = request.body()
        if (body.size > config.readMaxBytes) {
            throw ConnectException(ConnectCode.RESOURCE_EXHAUSTED, "request body exceeds limit")
        }
        return body
    }

    private fun connectTimeoutMillis(request: ConnectHttpRequest): Long? {
        val raw = request.header(ConnectHeaders.TIMEOUT_MS)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // The Connect spec limits the timeout to a positive integer of at most 10 digits.
        if (raw.length > 10 || !raw.all { it.isDigit() }) {
            throw ConnectException(
                ConnectCode.INVALID_ARGUMENT,
                "Connect-Timeout-Ms must be a positive integer of at most 10 digits",
            )
        }
        return raw.toLong()
    }

    private fun grpcTimeoutMillis(request: ConnectHttpRequest): Long? {
        val raw = request.header("grpc-timeout")?.trim()?.takeIf { it.length >= 2 } ?: return null
        val value = raw.dropLast(1).toLongOrNull() ?: return null
        return when (raw.last()) {
            'H' -> value * 3_600_000
            'M' -> value * 60_000
            'S' -> value * 1_000
            'm' -> value
            'u' -> value / 1_000
            'n' -> value / 1_000_000
            else -> null
        }
    }

    private fun negotiateUnaryResponseCompression(request: ConnectHttpRequest, size: Int): Compression? {
        if (size < config.compressMinBytes) return null
        return compressions.negotiate(request.header(ConnectHeaders.ACCEPT_ENCODING))
    }

    private fun negotiateStreamResponseCompression(request: ConnectHttpRequest, grpcWeb: Boolean): Compression? {
        val header = if (grpcWeb) ConnectHeaders.GRPC_ACCEPT_ENCODING else ConnectHeaders.CONNECT_ACCEPT_ENCODING
        return compressions.negotiate(request.header(header))
    }

    private fun codecContentType(encoding: String): String =
        if (encoding.equals("json", ignoreCase = true)) "application/json" else "application/proto"

    private fun decodeBase64Url(value: String): ByteArray {
        val padded = when (value.length % 4) {
            2 -> "$value=="
            3 -> "$value="
            else -> value
        }
        return Base64.getUrlDecoder().decode(padded)
    }

    private companion object {
        private const val HTTP_OK = 200
        private const val HTTP_UNSUPPORTED_MEDIA_TYPE = 415

        private val RESERVED_RESPONSE_HEADERS = setOf(
            "content-type", "content-length", "content-encoding",
            "grpc-status", "grpc-message", "grpc-status-details-bin",
            "grpc-encoding", "grpc-accept-encoding",
        )
    }
}
