package io.github.cgardev.example.demo

import com.google.protobuf.Any
import com.google.rpc.ErrorInfo
import io.github.cgardev.example.v1.AnyEnvelope
import io.github.cgardev.example.v1.CountRequest
import io.github.cgardev.example.v1.CountResponse
import io.github.cgardev.example.v1.EchoKotlinServiceGrpcKt
import io.github.cgardev.example.v1.EchoRequest
import io.github.cgardev.example.v1.EchoResponse
import io.github.cgardev.example.v1.FailRequest
import io.github.cgardev.example.v1.GetServerInfoRequest
import io.github.cgardev.example.v1.ServerInfo
import io.grpc.protobuf.StatusProto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.springframework.stereotype.Component

/**
 * The Kotlin counterpart of [EchoService]: identical behaviour, but implemented
 * with the gRPC Kotlin coroutine API (`suspend` functions and [Flow]) instead of
 * the Java `StreamObserver` API. Registered as a [io.grpc.BindableService] bean,
 * it is served over the Connect protocols exactly like the Java service.
 */
@Component
class KotlinEchoService : EchoKotlinServiceGrpcKt.EchoKotlinServiceCoroutineImplBase() {

    override suspend fun echo(request: EchoRequest): EchoResponse =
        EchoResponse.newBuilder().setMessage("echo: ${request.message}").build()

    override suspend fun getServerInfo(request: GetServerInfoRequest): ServerInfo =
        ServerInfo.newBuilder().setName("connect-kotlin-server").setVersion("0.1.0").build()

    override fun count(request: CountRequest): Flow<CountResponse> = flow {
        for (number in 1..request.to) {
            emit(CountResponse.newBuilder().setNumber(number).build())
        }
    }

    override suspend fun roundTrip(request: AnyEnvelope): AnyEnvelope =
        AnyEnvelope.newBuilder()
            .setPayload(request.payload)
            .setLabel("roundtrip:${request.label}")
            .build()

    override suspend fun fail(request: FailRequest): EchoResponse {
        val codeValue = if (request.grpcCode != 0) request.grpcCode else io.grpc.Status.Code.INVALID_ARGUMENT.value()
        val errorInfo = ErrorInfo.newBuilder()
            .setReason("DEMO_FAILURE")
            .setDomain("cgardev.example.v1")
            .putMetadata("requestedReason", request.reason)
            .build()
        val status = com.google.rpc.Status.newBuilder()
            .setCode(codeValue)
            .setMessage("intentional failure: ${request.reason}")
            .addDetails(Any.pack(errorInfo))
            .build()
        throw StatusProto.toStatusRuntimeException(status)
    }
}
