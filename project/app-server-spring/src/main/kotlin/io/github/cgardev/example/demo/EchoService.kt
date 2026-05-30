package io.github.cgardev.example.demo

import com.google.protobuf.Any
import com.google.rpc.ErrorInfo
import io.github.cgardev.example.v1.AnyEnvelope
import io.github.cgardev.example.v1.CountRequest
import io.github.cgardev.example.v1.CountResponse
import io.github.cgardev.example.v1.EchoRequest
import io.github.cgardev.example.v1.EchoResponse
import io.github.cgardev.example.v1.EchoServiceGrpc
import io.github.cgardev.example.v1.FailRequest
import io.github.cgardev.example.v1.GetServerInfoRequest
import io.github.cgardev.example.v1.ServerInfo
import io.grpc.protobuf.StatusProto
import io.grpc.stub.StreamObserver
import org.springframework.stereotype.Component

/**
 * Demonstration gRPC service. Registered as a Spring [Component] it is picked up
 * as an [io.grpc.BindableService] bean and served over the Connect protocols
 * without any further wiring.
 */
@Component
class EchoService : EchoServiceGrpc.EchoServiceImplBase() {

    override fun echo(request: EchoRequest, responseObserver: StreamObserver<EchoResponse>) {
        responseObserver.onNext(EchoResponse.newBuilder().setMessage("echo: ${request.message}").build())
        responseObserver.onCompleted()
    }

    override fun getServerInfo(request: GetServerInfoRequest, responseObserver: StreamObserver<ServerInfo>) {
        responseObserver.onNext(
            ServerInfo.newBuilder().setName("connect-kotlin-server").setVersion("0.1.0").build(),
        )
        responseObserver.onCompleted()
    }

    override fun count(request: CountRequest, responseObserver: StreamObserver<CountResponse>) {
        for (number in 1..request.to) {
            responseObserver.onNext(CountResponse.newBuilder().setNumber(number).build())
        }
        responseObserver.onCompleted()
    }

    override fun roundTrip(request: AnyEnvelope, responseObserver: StreamObserver<AnyEnvelope>) {
        // Echo the Any payload back unchanged and tag the label, so clients can
        // verify Any survives the proto and JSON round-trip through the server.
        responseObserver.onNext(
            AnyEnvelope.newBuilder()
                .setPayload(request.payload)
                .setLabel("roundtrip:${request.label}")
                .build(),
        )
        responseObserver.onCompleted()
    }

    override fun fail(request: FailRequest, responseObserver: StreamObserver<EchoResponse>) {
        val errorInfo = ErrorInfo.newBuilder()
            .setReason("DEMO_FAILURE")
            .setDomain("connect.demo.v1")
            .putMetadata("requestedReason", request.reason)
            .build()
        val status = com.google.rpc.Status.newBuilder()
            .setCode(io.grpc.Status.Code.INVALID_ARGUMENT.value())
            .setMessage("intentional failure: ${request.reason}")
            .addDetails(Any.pack(errorInfo))
            .build()
        responseObserver.onError(StatusProto.toStatusRuntimeException(status))
    }
}
