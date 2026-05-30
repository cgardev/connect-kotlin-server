package io.github.cgardev.library.connect.invoke

import com.google.protobuf.Message
import io.github.cgardev.library.connect.registry.ConnectMethodEntry
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientInterceptors
import io.grpc.Metadata
import io.grpc.stub.ClientCalls
import io.grpc.stub.MetadataUtils
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Result of a unary call: the response plus the captured leading/trailing metadata. */
data class UnaryResult(
    val message: Message,
    val headers: Metadata?,
    val trailers: Metadata?,
)

/**
 * A live server-streaming call. Iterating [messages] pulls responses as they are
 * produced; [headers] and [trailers] become available once the stream completes.
 */
class ServerStreamCall(
    val messages: Iterator<Message>,
    private val headersRef: AtomicReference<Metadata>,
    private val trailersRef: AtomicReference<Metadata>,
) {
    fun headers(): Metadata? = headersRef.get()
    fun trailers(): Metadata? = trailersRef.get()
}

/**
 * Issues in-process gRPC calls. Inbound metadata is attached to the call and the
 * response metadata is captured so the dispatcher can map gRPC trailers back to
 * the appropriate Connect/gRPC-Web representation. Failures surface as
 * [io.grpc.StatusRuntimeException].
 */
class InProcessInvoker(private val channelProvider: () -> Channel) {

    fun unary(
        entry: ConnectMethodEntry,
        request: Message,
        metadata: Metadata,
        deadlineMillis: Long?,
    ): UnaryResult {
        val headersRef = AtomicReference<Metadata>()
        val trailersRef = AtomicReference<Metadata>()
        val intercepted = decorate(metadata, headersRef, trailersRef)
        val response = ClientCalls.blockingUnaryCall(
            intercepted, entry.grpcMethod, callOptions(deadlineMillis), request,
        )
        return UnaryResult(response, headersRef.get(), trailersRef.get())
    }

    fun serverStream(
        entry: ConnectMethodEntry,
        request: Message,
        metadata: Metadata,
        deadlineMillis: Long?,
    ): ServerStreamCall {
        val headersRef = AtomicReference<Metadata>()
        val trailersRef = AtomicReference<Metadata>()
        val intercepted = decorate(metadata, headersRef, trailersRef)
        val iterator = ClientCalls.blockingServerStreamingCall(
            intercepted, entry.grpcMethod, callOptions(deadlineMillis), request,
        )
        return ServerStreamCall(iterator, headersRef, trailersRef)
    }

    private fun decorate(
        metadata: Metadata,
        headersRef: AtomicReference<Metadata>,
        trailersRef: AtomicReference<Metadata>,
    ): Channel = ClientInterceptors.intercept(
        channelProvider(),
        MetadataUtils.newAttachHeadersInterceptor(metadata),
        MetadataUtils.newCaptureMetadataInterceptor(headersRef, trailersRef),
    )

    private fun callOptions(deadlineMillis: Long?): CallOptions {
        var options = CallOptions.DEFAULT
        if (deadlineMillis != null) {
            options = options.withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS)
        }
        return options
    }
}
