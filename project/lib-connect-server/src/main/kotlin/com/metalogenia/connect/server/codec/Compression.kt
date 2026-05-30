package com.metalogenia.connect.server.codec

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Symmetric compression for request/response payloads. The Connect, Connect
 * streaming and gRPC-Web protocols all negotiate compression through different
 * header families but share the same algorithm names, so a single registry
 * keyed by name covers every case.
 */
interface Compression {
    val name: String
    fun compress(data: ByteArray): ByteArray
    fun decompress(data: ByteArray): ByteArray
}

object GzipCompression : Compression {
    override val name: String = "gzip"

    override fun compress(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(data.size)
        GZIPOutputStream(output).use { it.write(data) }
        return output.toByteArray()
    }

    override fun decompress(data: ByteArray): ByteArray {
        GZIPInputStream(ByteArrayInputStream(data)).use { return it.readBytes() }
    }
}

/**
 * Resolves compression algorithms by their wire name. The `identity` name (and
 * a blank/absent value) means "no compression" and resolves to `null`.
 */
class CompressionRegistry(compressions: List<Compression> = listOf(GzipCompression)) {

    private val byName = compressions.associateBy { it.name.lowercase() }

    /** The names advertised in `Accept-Encoding`-style headers. */
    val acceptedEncodings: String = compressions.joinToString(",") { it.name }

    fun resolve(name: String?): Compression? {
        val normalized = name?.trim()?.lowercase()
        if (normalized.isNullOrEmpty() || normalized == "identity") return null
        return byName[normalized]
    }

    fun supports(name: String?): Boolean {
        val normalized = name?.trim()?.lowercase()
        return normalized.isNullOrEmpty() || normalized == "identity" || byName.containsKey(normalized)
    }

    /** Picks the first accepted encoding the registry knows about, or `null` for identity. */
    fun negotiate(acceptHeader: String?): Compression? {
        if (acceptHeader.isNullOrBlank()) return null
        for (raw in acceptHeader.split(',')) {
            val token = raw.substringBefore(';').trim().lowercase()
            if (token == "identity") return null
            byName[token]?.let { return it }
        }
        return null
    }
}
