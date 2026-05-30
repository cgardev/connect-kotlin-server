package io.github.cgardev.library.connect.registry

import com.google.protobuf.Descriptors
import com.google.protobuf.Message
import io.grpc.MethodDescriptor

/**
 * A single RPC method resolved from a gRPC [io.grpc.BindableService], holding
 * everything the dispatcher needs to route an HTTP request to the in-process
 * gRPC pipeline and to (de)serialize its messages.
 */
data class ConnectMethodEntry(
    /** The full method name, identical to the request path without the leading slash. */
    val fullMethodName: String,
    val serviceName: String,
    val methodName: String,
    val grpcMethod: MethodDescriptor<Message, Message>,
    val requestPrototype: Message,
    val responsePrototype: Message,
    val type: MethodDescriptor.MethodType,
    /** True when the proto method is annotated `idempotency_level = NO_SIDE_EFFECTS` (GET-eligible). */
    val noSideEffects: Boolean,
    val protoMethod: Descriptors.MethodDescriptor?,
)
