package io.github.cgardev.library.connect.error

import com.google.protobuf.Any as ProtoAny
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import io.grpc.protobuf.StatusProto

/**
 * Translates failures raised by the in-process gRPC pipeline into a
 * [ConnectException], preserving the status code, message and any
 * `google.rpc.Status` details attached to the gRPC trailers.
 */
object ConnectErrorMapper {

    /** Normalizes any throwable into a [ConnectException]. */
    fun toConnectException(throwable: Throwable): ConnectException {
        return when (throwable) {
            is ConnectException -> throwable
            is StatusRuntimeException -> fromStatus(throwable.status, throwable)
            is StatusException -> fromStatus(throwable.status, throwable)
            else -> ConnectException(
                code = ConnectCode.UNKNOWN,
                message = throwable.message ?: throwable.javaClass.simpleName,
                cause = throwable,
            )
        }
    }

    private fun fromStatus(status: Status, cause: Throwable): ConnectException {
        val code = ConnectCode.fromGrpc(status.code)
        val message = status.description ?: status.code.name
        return ConnectException(code = code, message = message, details = extractDetails(cause), cause = cause)
    }

    /** Reads the `grpc-status-details-bin` trailer (a `google.rpc.Status`) if present. */
    private fun extractDetails(cause: Throwable): List<ConnectErrorDetail> {
        val rpcStatus = runCatching { StatusProto.fromThrowable(cause) }.getOrNull() ?: return emptyList()
        return rpcStatus.detailsList.map { detail: ProtoAny -> ConnectErrorDetail(detail) }
    }
}
