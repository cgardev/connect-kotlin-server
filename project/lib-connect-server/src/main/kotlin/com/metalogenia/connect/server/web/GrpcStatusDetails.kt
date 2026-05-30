package com.metalogenia.connect.server.web

import com.metalogenia.connect.server.error.ConnectException

/**
 * Builds the `grpc-status-details-bin` value: a serialized `google.rpc.Status`
 * carrying the numeric code, message and error details. Used by the gRPC-Web
 * trailer frame to convey structured error details.
 */
object GrpcStatusDetails {

    fun toBytes(error: ConnectException): ByteArray {
        val status = com.google.rpc.Status.newBuilder()
            .setCode(error.code.number)
            .setMessage(error.message)
            .addAllDetails(error.details.map { it.any })
            .build()
        return status.toByteArray()
    }
}
