package io.github.cgardev.library.connect.error

import com.google.protobuf.Any as ProtoAny

/**
 * A single error detail, carrying an arbitrary protobuf message serialized as a
 * `google.protobuf.Any`. Serialized to the `details` array of the Connect error
 * JSON as `{ "type": ..., "value": <base64> }`.
 */
data class ConnectErrorDetail(val any: ProtoAny)

/**
 * The server-side representation of a Connect error. Handlers and the dispatcher
 * throw this to produce a Connect error response (unary JSON body, Connect
 * end-of-stream message, or gRPC-Web trailer frame depending on the protocol).
 */
class ConnectException(
    val code: ConnectCode,
    override val message: String = "",
    val details: List<ConnectErrorDetail> = emptyList(),
    cause: Throwable? = null,
) : RuntimeException(message, cause)
