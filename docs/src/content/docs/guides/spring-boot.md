---
title: "Spring Boot"
description: "Integrate connect-kotlin-server with Spring Boot using auto-configuration or manual wiring."
---

## Overview

Connect Kotlin Server provides two Spring modules for serving your `io.grpc.BindableService` implementations over the Connect, Connect-streaming and gRPC-Web protocols:

- **`connect-kotlin-server-spring`** — Spring components only: `ConnectServerLifecycle` and `ConnectServerProperties`. Use this when you want to wire the server yourself.
- **`connect-kotlin-server-spring-boot-autoconfigure`** — Full auto-configuration: discovers your services and interceptors, builds the server, and manages its lifecycle. Use this for zero-wiring projects.

Both modules are built on the Spring convention of splitting beans and configuration (`*-spring`) from auto-configuration (`*-spring-boot-autoconfigure`), ensuring you only load what you need.

## Quick start with auto-configuration

The simplest path: add the autoconfigure starter, mark your services with `@Component`, and set the application to use no servlet container.

### Dependencies

```kotlin title="build.gradle.kts"
dependencies {
    implementation("io.github.cgardev:connect-kotlin-server-spring-boot-autoconfigure:<commit>")
    implementation("org.springframework.boot:spring-boot-starter")
}
```

### Application class

```kotlin
@SpringBootApplication
class MyApplication

fun main(args: Array<String>) {
    runApplication<MyApplication>(*args)
}
```

### Service

Any `io.grpc.BindableService` bean is automatically discovered and served. Implement the generated gRPC base class and register it as a Spring `@Component`:

```kotlin
@Component
class MyService : MyServiceGrpc.MyServiceImplBase() {
    override fun unary(request: Request, responseObserver: StreamObserver<Response>) {
        responseObserver.onNext(Response.newBuilder().build())
        responseObserver.onCompleted()
    }
    
    override fun serverStream(request: Request, responseObserver: StreamObserver<Response>) {
        for (i in 1..10) {
            responseObserver.onNext(Response.newBuilder().setNumber(i).build())
        }
        responseObserver.onCompleted()
    }
}
```

### Configuration

Tell Spring to use no servlet container (Netty from the Connect library is the HTTP server):

```properties title="application.properties"
spring.main.web-application-type=none
connect.server.port=8080
```

That's it. The auto-configuration will:

1. Discover all `BindableService` and `ServerInterceptor` beans
2. Build a `ConnectServer` from the `ConnectServerProperties`
3. Register a `ConnectServerLifecycle` SmartLifecycle that starts and stops the server with the Spring context

## Manual wiring with the components module

If you prefer to wire the beans yourself, depend on `connect-kotlin-server-spring` instead:

```kotlin title="build.gradle.kts"
dependencies {
    implementation("io.github.cgardev:connect-kotlin-server-spring:<commit>")
}
```

Then declare the server and lifecycle as `@Bean`s:

```kotlin
@Configuration
class ConnectConfiguration {
    
    @Bean
    fun connectServer(services: List<BindableService>, properties: ConnectServerProperties): ConnectServer =
        ConnectServer(
            services = services,
            config = properties.toConfig(),
        )
    
    @Bean
    fun connectServerLifecycle(connectServer: ConnectServer): ConnectServerLifecycle =
        ConnectServerLifecycle(connectServer)
}
```

The `ConnectServerLifecycle` will still manage the server's startup and shutdown.

## Lifecycle: SmartLifecycle integration

`ConnectServerLifecycle` integrates the (Spring-free) `ConnectServer` into Spring's application context:

- **Auto-start:** the server starts automatically when the context is ready
- **Phase:** runs in the late phase (`Int.MAX_VALUE`), after all other beans are initialized
- **Graceful shutdown:** the server is closed when the context closes, allowing in-flight requests to complete
- **Running state:** an internal flag tracks whether the server is running

```kotlin
interface SmartLifecycle {
    fun start()           // Calls connectServer.start()
    fun stop()            // Calls connectServer.close()
    fun isRunning(): Boolean
    fun isAutoStartup(): Boolean = true
    fun getPhase(): Int = Int.MAX_VALUE  // Start last, stop first
}
```

## Configuration properties

All server options are tunable through the `connect.server.*` namespace, mapped by `ConnectServerProperties`:

```properties
connect.server.enabled=true                    # Master switch (default: true)
connect.server.host=0.0.0.0                    # Bind address (default: 0.0.0.0)
connect.server.port=8080                       # Port (default: 8080)
connect.server.basePath=/                      # Base path for all routes (default: /)
connect.server.requireProtocolVersion=false    # Enforce protocol version header (default: false)
connect.server.getEnabled=true                 # Enable GET for idempotent unary (default: true)
connect.server.compressMinBytes=1024           # Min response size to gzip (default: 1024)
connect.server.readMaxBytes=4194304            # Max request body size in bytes (default: 4MB)
connect.server.shutdownGraceMillis=5000        # Grace period for in-flight requests (default: 5s)

# CORS
connect.server.cors.enabled=true               # Enable CORS (default: true)
connect.server.cors.allowedOrigins=*           # Allowed origins (default: * / all)
connect.server.cors.allowCredentials=true      # Allow credentials in CORS (default: true)
connect.server.cors.allowPrivateNetwork=true   # Allow private network CORS (default: true)
connect.server.cors.maxAgeSeconds=14400        # Max age of preflight cache (default: 4h)
```

> [!NOTE]
> `ConnectServerProperties` maps these Spring-bound values to the core's plain `ConnectServerConfig`, keeping the library free of Spring dependencies.

## ServerInterceptor beans

Just as with gRPC servers, any `io.grpc.ServerInterceptor` bean is automatically applied to the request pipeline:

```kotlin
@Component
class LoggingInterceptor : ServerInterceptor {
    override fun interceptCall(
        call: ServerCall<*, *>,
        headers: Metadata,
        next: ServerCallHandler<*, *>,
    ): ServerCall.Listener<*> {
        println("Received call: ${call.methodDescriptor.fullMethodName}")
        return next.startCall(call, headers)
    }
}
```

Interceptors run on the in-process gRPC channel that sits between the HTTP layer and your service implementations, exactly as they would on a stand-alone gRPC server.

## Disabling auto-configuration

Set `connect.server.enabled=false` to skip auto-configuration entirely without removing the dependency:

```properties
connect.server.enabled=false
```

## Why no servlet container?

The Connect server manages its own embedded Netty HTTP/1.1 transport. Setting `spring.main.web-application-type=none` prevents Spring from trying to start an unnecessary servlet container (Tomcat, Jetty, etc.) and reduces startup time and memory footprint.

## Example project

See `project/app-server-spring` in the repository for a complete, runnable Spring Boot application serving a demo `EchoService` over Connect and gRPC-Web. Run it with:

```bash
./gradlew :project:app-server-spring:bootRun
```

Then test it:

```bash
# Connect unary, JSON
curl -X POST http://localhost:8080/cgardev.example.v1.EchoService/Echo \
  -H 'Content-Type: application/json' \
  -d '{"message":"hello"}'

# gRPC-Web server-streaming
curl http://localhost:8080/cgardev.example.v1.EchoService/Count \
  -H 'Content-Type: application/proto' \
  -d 'to: 5'
```
