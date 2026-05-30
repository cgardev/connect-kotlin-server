package io.github.cgardev.library.connect.error

import io.grpc.Status

/**
 * The canonical Connect error codes. Each code carries its three on-the-wire
 * representations: the numeric value (gRPC-Web trailer), the lowercase
 * snake_case name (Connect unary JSON and Connect end-of-stream), and the HTTP
 * status used for Connect unary error responses.
 *
 * The enum name follows the gRPC upper-case convention (used in the Connect
 * end-of-stream message), with a deliberate alias for the spelling differences
 * between the gRPC status names and the Connect code names.
 */
enum class ConnectCode(
    val number: Int,
    val wireName: String,
    val httpStatus: Int,
) {
    CANCELED(1, "canceled", 499),
    UNKNOWN(2, "unknown", 500),
    INVALID_ARGUMENT(3, "invalid_argument", 400),
    DEADLINE_EXCEEDED(4, "deadline_exceeded", 504),
    NOT_FOUND(5, "not_found", 404),
    ALREADY_EXISTS(6, "already_exists", 409),
    PERMISSION_DENIED(7, "permission_denied", 403),
    RESOURCE_EXHAUSTED(8, "resource_exhausted", 429),
    FAILED_PRECONDITION(9, "failed_precondition", 400),
    ABORTED(10, "aborted", 409),
    OUT_OF_RANGE(11, "out_of_range", 400),
    UNIMPLEMENTED(12, "unimplemented", 501),
    INTERNAL(13, "internal", 500),
    UNAVAILABLE(14, "unavailable", 503),
    DATA_LOSS(15, "data_loss", 500),
    UNAUTHENTICATED(16, "unauthenticated", 401);

    /**
     * The upper-case name used by the Connect end-of-stream message and by gRPC
     * status names (for example `INVALID_ARGUMENT`).
     */
    val grpcName: String get() = name

    companion object {
        private val BY_NUMBER = entries.associateBy { it.number }
        private val BY_WIRE_NAME = entries.associateBy { it.wireName }

        /** Resolves a code from its numeric value, defaulting to [UNKNOWN]. */
        fun fromNumber(number: Int): ConnectCode = BY_NUMBER[number] ?: UNKNOWN

        /** Resolves a code from its lowercase wire name, defaulting to [UNKNOWN]. */
        fun fromWireName(wireName: String): ConnectCode = BY_WIRE_NAME[wireName.lowercase()] ?: UNKNOWN

        /**
         * Maps a gRPC status code to the equivalent Connect code. The mapping is
         * one-to-one except for `OK`, which has no Connect error representation
         * and therefore resolves to [UNKNOWN] when treated as an error.
         */
        fun fromGrpc(code: Status.Code): ConnectCode = when (code) {
            Status.Code.OK -> UNKNOWN
            Status.Code.CANCELLED -> CANCELED
            Status.Code.UNKNOWN -> UNKNOWN
            Status.Code.INVALID_ARGUMENT -> INVALID_ARGUMENT
            Status.Code.DEADLINE_EXCEEDED -> DEADLINE_EXCEEDED
            Status.Code.NOT_FOUND -> NOT_FOUND
            Status.Code.ALREADY_EXISTS -> ALREADY_EXISTS
            Status.Code.PERMISSION_DENIED -> PERMISSION_DENIED
            Status.Code.RESOURCE_EXHAUSTED -> RESOURCE_EXHAUSTED
            Status.Code.FAILED_PRECONDITION -> FAILED_PRECONDITION
            Status.Code.ABORTED -> ABORTED
            Status.Code.OUT_OF_RANGE -> OUT_OF_RANGE
            Status.Code.UNIMPLEMENTED -> UNIMPLEMENTED
            Status.Code.INTERNAL -> INTERNAL
            Status.Code.UNAVAILABLE -> UNAVAILABLE
            Status.Code.DATA_LOSS -> DATA_LOSS
            Status.Code.UNAUTHENTICATED -> UNAUTHENTICATED
        }
    }
}
