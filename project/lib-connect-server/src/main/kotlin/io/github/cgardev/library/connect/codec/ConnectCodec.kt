package io.github.cgardev.library.connect.codec

import com.google.protobuf.Message
import com.google.protobuf.TypeRegistry
import com.google.protobuf.util.JsonFormat
import java.nio.charset.StandardCharsets

/**
 * Serializes and parses protobuf messages for a single Connect codec. The codec
 * is selected purely from the request `Content-Type` (`proto` or `json`).
 */
interface ConnectCodec {
    /** The codec name as it appears in the content-type and GET `encoding` parameter. */
    val name: String

    fun serialize(message: Message): ByteArray

    /** Parses [data] into a message of the same type as [prototype]. */
    fun deserialize(data: ByteArray, prototype: Message): Message
}

/** Binary protobuf codec (`application/proto`). */
object ProtoCodec : ConnectCodec {
    override val name: String = "proto"

    override fun serialize(message: Message): ByteArray = message.toByteArray()

    override fun deserialize(data: ByteArray, prototype: Message): Message =
        prototype.parserForType.parseFrom(data)
}

/**
 * Protobuf-JSON codec (`application/json`), backed by `protobuf-java-util`'s
 * canonical [JsonFormat]. The [TypeRegistry] lets the printer/parser resolve
 * `google.protobuf.Any` payloads.
 */
class JsonCodec(typeRegistry: TypeRegistry) : ConnectCodec {
    override val name: String = "json"

    private val printer: JsonFormat.Printer =
        JsonFormat.printer().usingTypeRegistry(typeRegistry).omittingInsignificantWhitespace()
    private val parser: JsonFormat.Parser =
        JsonFormat.parser().usingTypeRegistry(typeRegistry).ignoringUnknownFields()

    override fun serialize(message: Message): ByteArray =
        printer.print(message).toByteArray(StandardCharsets.UTF_8)

    override fun deserialize(data: ByteArray, prototype: Message): Message {
        val builder = prototype.newBuilderForType()
        parser.merge(String(data, StandardCharsets.UTF_8), builder)
        return builder.build()
    }
}

/** Resolves codecs by name for the negotiated content-type. */
class CodecRegistry(private val proto: ConnectCodec, private val json: ConnectCodec) {

    fun byName(name: String): ConnectCodec? = when (name.lowercase()) {
        "proto" -> proto
        "json" -> json
        else -> null
    }
}
