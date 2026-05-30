package com.metalogenia.connect.server.registry

import com.google.protobuf.DescriptorProtos.MethodOptions.IdempotencyLevel
import com.google.protobuf.Descriptors
import com.google.protobuf.Message
import com.google.protobuf.TypeRegistry
import io.grpc.BindableService
import io.grpc.MethodDescriptor
import io.grpc.protobuf.ProtoServiceDescriptorSupplier
import org.slf4j.LoggerFactory

/**
 * Builds and holds the catalog of RPC methods exposed by every discovered
 * [BindableService]. The catalog is keyed by full method name so the dispatcher
 * can resolve an inbound request path in constant time. It also exposes a
 * protobuf [TypeRegistry] aggregating all message types, used by the JSON codec
 * to render `google.protobuf.Any` values.
 */
class ConnectMethodRegistry(services: List<BindableService>) {

    private val log = LoggerFactory.getLogger(ConnectMethodRegistry::class.java)

    private val entries: Map<String, ConnectMethodEntry>
    val typeRegistry: TypeRegistry

    init {
        val resolved = LinkedHashMap<String, ConnectMethodEntry>()
        val files = LinkedHashSet<Descriptors.FileDescriptor>()

        for (service in services) {
            val definition = service.bindService()
            val serviceDescriptor = definition.serviceDescriptor
            val protoService = (serviceDescriptor.schemaDescriptor as? ProtoServiceDescriptorSupplier)?.serviceDescriptor
            protoService?.file?.let { collectFiles(it, files) }

            for (method in definition.methods) {
                val grpcMethod = uncheckedMethod(method.methodDescriptor)
                val requestPrototype = prototypeOf(grpcMethod.requestMarshaller)
                val responsePrototype = prototypeOf(grpcMethod.responseMarshaller)
                if (requestPrototype == null || responsePrototype == null) {
                    log.warn("Skipping non-protobuf method {}", grpcMethod.fullMethodName)
                    continue
                }
                val bareName = grpcMethod.fullMethodName.substringAfterLast('/')
                val protoMethod = protoService?.findMethodByName(bareName)
                val noSideEffects =
                    protoMethod?.options?.idempotencyLevel == IdempotencyLevel.NO_SIDE_EFFECTS

                resolved[grpcMethod.fullMethodName] = ConnectMethodEntry(
                    fullMethodName = grpcMethod.fullMethodName,
                    serviceName = serviceDescriptor.name,
                    methodName = bareName,
                    grpcMethod = grpcMethod,
                    requestPrototype = requestPrototype,
                    responsePrototype = responsePrototype,
                    type = grpcMethod.type,
                    noSideEffects = noSideEffects,
                    protoMethod = protoMethod,
                )
            }
        }

        entries = resolved
        typeRegistry = buildTypeRegistry(files)
        log.info("Connect registry initialized with {} method(s) across {} service(s)", entries.size, services.size)
    }

    fun find(fullMethodName: String): ConnectMethodEntry? = entries[fullMethodName]

    fun methods(): Collection<ConnectMethodEntry> = entries.values

    private fun collectFiles(file: Descriptors.FileDescriptor, into: MutableSet<Descriptors.FileDescriptor>) {
        if (!into.add(file)) return
        file.dependencies.forEach { collectFiles(it, into) }
    }

    private fun buildTypeRegistry(files: Set<Descriptors.FileDescriptor>): TypeRegistry {
        val builder = TypeRegistry.newBuilder()
        files.forEach { file -> file.messageTypes.forEach { builder.add(it) } }
        return builder.build()
    }

    private fun prototypeOf(marshaller: MethodDescriptor.Marshaller<Message>): Message? {
        val prototypeMarshaller = marshaller as? MethodDescriptor.PrototypeMarshaller<Message> ?: return null
        return prototypeMarshaller.messagePrototype as? Message
    }

    @Suppress("UNCHECKED_CAST")
    private fun uncheckedMethod(method: MethodDescriptor<*, *>): MethodDescriptor<Message, Message> =
        method as MethodDescriptor<Message, Message>
}
