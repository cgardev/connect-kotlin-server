package com.metalogenia.server.demo

import com.google.protobuf.Any
import com.google.rpc.ErrorInfo
import com.metalogenia.connect.demo.v1.CountRequest
import com.metalogenia.connect.demo.v1.CountResponse
import com.metalogenia.connect.demo.v1.EchoRequest
import com.metalogenia.connect.demo.v1.EchoResponse
import com.metalogenia.connect.demo.v1.EchoServiceGrpc
import com.metalogenia.connect.demo.v1.FailRequest
import com.metalogenia.connect.demo.v1.GetServerInfoRequest
import com.metalogenia.connect.demo.v1.ServerInfo
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
            ServerInfo.newBuilder().setName("connect-rpc-kotlin-server").setVersion("0.1.0").build(),
        )
        responseObserver.onCompleted()
    }

    override fun count(request: CountRequest, responseObserver: StreamObserver<CountResponse>) {
        for (number in 1..request.to) {
            responseObserver.onNext(CountResponse.newBuilder().setNumber(number).build())
        }
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
