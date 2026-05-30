---
title: "Architecture"
description: "How connect-kotlin-server handles requests: HTTP termination, method resolution, in-process gRPC invocation, and response streaming."
---

## Request flow

A Connect request travels through five key stages:

```
HTTP/1.1 Request (Netty)
        ↓
Snapshot on event loop; dispatch to virtual thread
        ↓
ConnectHttpRequest/Response (transport-neutral)
        ↓
ConnectDispatcher resolves method in registry; negotiates codec
        ↓
InProcessInvoker calls in-process gRPC channel (preserves interceptors)
        ↓
ClientCalls invokes handler via gRPC stubs
        ↓
Response streamed back; metadata mapped to Connect/gRPC-Web format
```

### HTTP termination (Netty)

The embedded Netty HTTP/1.1 server ([`ConnectNettyServer`](https://github.com/cgardev/connect-kotlin-server/blob/main/project/lib-connect-server/src/main/kotlin/io/github/cgardev/library/connect/netty/ConnectNettyServer.kt)) binds to a configured host and port. It uses a standard Netty pipeline:

- `HttpServerCodec` decodes HTTP messages
- `HttpObjectAggregator` reads the full request body (bounded by `readMaxBytes`)
- `ConnectChannelHandler` bridges to the application logic

### Off the event loop

[`ConnectChannelHandler`](https://github.com/cgardev/connect-kotlin-server/blob/main/project/lib-connect-server/src/main/kotlin/io/github/cgardev/library/connect/netty/ConnectChannelHandler.kt) snapshots the inbound `FullHttpRequest` (copying headers and body into a heap-allocated `NettyConnectRequest`) on the Netty event loop thread, then hands it off to a virtual-thread executor. This ensures the blocking gRPC calls never run on the event loop.

```kotlin
executor.execute {
    val response = NettyConnectResponse(ctx, keepAlive)
    handler.handle(request, response)
    response.finish()
}
```

### Transport-neutral abstraction

The dispatcher and all Connect logic work against [`ConnectHttpRequest`](https://github.com/cgardev/connect-kotlin-server/blob/main/project/lib-connect-server/src/main/kotlin/io/github/cgardev/library/connect/transport/ConnectHttpExchange.kt) and [`ConnectHttpResponse`](https://github.com/cgardev/connect-kotlin-server/blob/main/project/lib-connect-server/src/main/kotlin/io/github/cgardev/library/connect/transport/ConnectHttpExchange.kt) interfaces. This decouples the Connect protocol logic from any specific HTTP server, making it possible to run connect-kotlin-server on Netty, servlet containers, or other HTTP stacks.

### Method resolution and dispatch

[`ConnectHttpHandler`](https://github.com/cgardev/connect-kotlin-server/blob/main/project/lib-connect-server/src/main/kotlin/io/github/cgardev/library/connect/web/ConnectHttpHandler.kt) is the entry point. It applies CORS headers, handles preflight requests, answers the liveness probe (`/health`), and passes RPC requests to [`ConnectDispatcher`](https://github.com/cgardev/connect-kotlin-server/blob/main/project/lib-connect-server/src/main/kotlin/io/github/cgardev/library/connect/web/ConnectDispatcher.kt).

The dispatcher parses the request path (format: `package.Service/Method`) and looks it up in [`ConnectMethodRegistry`](https://github.com/cgardev/connect-kotlin-server/blob/main/project/lib-connect-server/src/main/kotlin/io/github/cgardev/library/connect/registry/ConnectMethodRegistry.kt) — a map of full method names to [`ConnectMethodEntry`](https://github.com/cgardev/connect-kotlin-server/blob/main/project/lib-connect-server/src/main/kotlin/io/github/cgardev/library/connect/registry/ConnectMethodRegistry.kt) objects, each holding the gRPC `MethodDescriptor`, request/response prototypes, and any `NO_SIDE_EFFECTS` idempotency marker.

Negotiation selects the protocol (Connect unary, Connect streaming, or gRPC-Web) and codec (Protobuf binary or JSON) from the `Content-Type` header.

### In-process invocation

Rather than sending the request over the network to another gRPC server, [`InProcessInvoker`](https://github.com/cgardev/connect-kotlin-server/blob/main/project/lib-connect-server/src/main/kotlin/io/github/cgardev/library/connect/invoke/InProcessInvoker.kt) uses gRPC's `ClientCalls` API to invoke handlers on an [`InProcessGrpcChannel`](https://github.com/cgardev/connect-kotlin-server/blob/main/project/lib-connect-server/src/main/kotlin/io/github/cgardev/library/connect/channel/InProcessGrpcChannel.kt).

The in-process channel hosts the discovered services with their `ServerInterceptor` pipeline intact — exactly as a normal gRPC server would. This preserves authentication, logging, deadline enforcement, and any other interceptor logic:

```kotlin
val definition = service.bindService()
val finalDefinition =
    if (interceptors.isEmpty()) definition
    else ServerInterceptors.interceptForward(definition, interceptors)
serverBuilder.addService(finalDefinition)
```

When a call is made, `InProcessInvoker` wraps the request metadata and captures the response metadata (headers and trailers) so the dispatcher can map gRPC status and metadata back to Connect or gRPC-Web error JSON.

```kotlin
val intercepted = ClientInterceptors.intercept(
    channelProvider(),
    MetadataUtils.newAttachHeadersInterceptor(metadata),
    MetadataUtils.newCaptureMetadataInterceptor(headersRef, trailersRef),
)
val response = ClientCalls.blockingUnaryCall(
    intercepted, entry.grpcMethod, callOptions(deadlineMillis), request,
)
```

### Response streaming and framing

For unary calls, the handler's response is encoded (Protobuf or JSON) and written directly. For server-streaming, responses are framed with the appropriate envelope (Connect or gRPC-Web) and sent as chunked transfer encoding:

```kotlin
response.setHeader("Transfer-Encoding", "chunked")
while (iterator.hasNext()) {
    val message = iterator.next()
    val envelope = encodeEnvelope(message)
    response.output.write(envelope)
    response.output.flush()
}
```

Errors surface as [`StatusRuntimeException`](https://javadoc.io/doc/io.grpc/grpc-api/latest/io/grpc/StatusRuntimeException.html) from the gRPC pipeline and are mapped to Connect error JSON (`code`, `message`, `details`) or gRPC-Web trailers by [`ConnectErrorMapper`](https://github.com/cgardev/connect-kotlin-server/blob/main/project/lib-connect-server/src/main/kotlin/io/github/cgardev/library/connect/error/ConnectErrorMapper.kt).

## Module layout

The project is split into four modules:

| Module | Role | Dependencies |
|--------|------|---|
| **`lib-connect-server`** | Core Connect server: HTTP termination, protocol negotiation, in-process invocation, gRPC interceptor integration. | gRPC, Netty, Protobuf, SLF4J. **No Spring or Servlet API.** |
| **`lib-connect-server-spring`** | Spring components: `ConnectServerProperties` config class and `ConnectServerLifecycle` (a Spring `SmartLifecycle` bean). | `lib-connect-server`, Spring Framework. Allows manual bean wiring. |
| **`lib-connect-server-spring-boot-autoconfigure`** | Spring Boot auto-configuration starter. Discovers all `BindableService` and `ServerInterceptor` beans and auto-wires the server. | `lib-connect-server-spring`, Spring Boot. Zero-wiring integration. |
| **`app-server-spring`** | Example application with a demo `EchoService`. | All of the above, plus application code. |

This modular layout allows:

- **Spring-free deployments:** use `lib-connect-server` in non-Spring apps (CLI, serverless, custom frameworks).
- **Manual Spring wiring:** use `lib-connect-server-spring` if you want control.
- **Spring Boot auto-wiring:** use the starter for zero boilerplate in Spring Boot applications.

## Why in-process?

Running the gRPC services on an in-process channel instead of a network socket eliminates:

- **Network overhead:** no syscalls, no TCP/IP stack, no marshalling/demarshalling overhead for the internal hop.
- **A separate proxy binary:** many teams deploy a Go proxy (e.g., [Vanguard](https://connectrpc.com/docs/go/deployment#vanguard)) or polyglot container to transcode Connect/gRPC-Web into gRPC. This adds operational complexity, memory footprint, and latency. connect-kotlin-server collapses that into one JVM process.
- **Configuration drift:** the gRPC handlers and the Connect server are deployed as one unit, with shared interceptors and credentials, eliminating sync and versioning problems.

The trade-off is a single JVM process: if you need true multi-process isolation or cross-machine deployments, you'd run a real network socket or a separate gRPC server.
