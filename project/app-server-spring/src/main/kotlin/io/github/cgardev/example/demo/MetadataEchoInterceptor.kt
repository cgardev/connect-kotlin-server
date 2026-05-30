package io.github.cgardev.example.demo

import io.grpc.ForwardingServerCall.SimpleForwardingServerCall
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import org.springframework.stereotype.Component

/**
 * Demonstration [ServerInterceptor]: when a request carries the `x-echo` header,
 * it copies that value into a response **leading header** (`x-echo-header`) and a
 * response **trailer** (`x-echo-trailer`). Registered as a Spring bean, it is
 * applied to the in-process gRPC pipeline by the Connect server — exercising
 * request-metadata propagation and both response-metadata directions.
 */
@Component
class MetadataEchoInterceptor : ServerInterceptor {

    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        val echo = headers.get(ECHO_KEY) ?: return next.startCall(call, headers)
        val forwarding = object : SimpleForwardingServerCall<ReqT, RespT>(call) {
            override fun sendHeaders(responseHeaders: Metadata) {
                responseHeaders.put(HEADER_KEY, echo)
                super.sendHeaders(responseHeaders)
            }

            override fun close(status: Status, trailers: Metadata) {
                trailers.put(TRAILER_KEY, echo)
                super.close(status, trailers)
            }
        }
        return next.startCall(forwarding, headers)
    }

    private companion object {
        val ECHO_KEY: Metadata.Key<String> = Metadata.Key.of("x-echo", Metadata.ASCII_STRING_MARSHALLER)
        val HEADER_KEY: Metadata.Key<String> = Metadata.Key.of("x-echo-header", Metadata.ASCII_STRING_MARSHALLER)
        val TRAILER_KEY: Metadata.Key<String> = Metadata.Key.of("x-echo-trailer", Metadata.ASCII_STRING_MARSHALLER)
    }
}
