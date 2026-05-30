---
title: "Getting Started"
description: "Install connect-kotlin-server, set up a gRPC service, and run it over Connect protocol."
---

This guide walks through installing connect-kotlin-server, defining a gRPC service, and running the embedded Connect server end-to-end.

## Requirements

- JDK 24 or newer.
- gRPC services generated with `grpc-java` (any `io.grpc.BindableService`).

## Installation

> [!IMPORTANT]
> There are no published releases yet. The project is in alpha, versioned **by commit** — depend on a specific commit and treat every commit as potentially breaking (see the [README](/connect-kotlin-server/) for Versioning & compatibility).

### Via JitPack

Pull a specific commit through [JitPack](https://jitpack.io):

```kotlin title="build.gradle.kts"
repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    // Replace <commit> with the exact short commit SHA you want to pin to.
    implementation("com.github.cgardev:connect-kotlin-server:<commit>")
}
```

### Local snapshot build

Or clone the repository and publish to your local Maven repository:

```bash
git clone https://github.com/cgardev/connect-kotlin-server.git
cd connect-kotlin-server
./gradlew :project:lib-connect-server:publishToMavenLocal
```

Then depend on the snapshot in your `build.gradle.kts`:

```kotlin
repositories {
    mavenLocal()
}

dependencies {
    implementation("io.github.cgardev:connect-kotlin-server:0.0.0-SNAPSHOT")
}
```

## Defining a service

Start by writing a Protobuf service definition. Here's the demo service from the example:

```proto title="echo.proto"
syntax = "proto3";

package cgardev.example.v1;

option java_multiple_files = true;
option java_package = "io.github.cgardev.example.v1";

service EchoService {
  rpc Echo(EchoRequest) returns (EchoResponse);

  rpc GetServerInfo(GetServerInfoRequest) returns (ServerInfo) {
    option idempotency_level = NO_SIDE_EFFECTS;
  }
}

message EchoRequest {
  string message = 1;
}

message EchoResponse {
  string message = 1;
}

message GetServerInfoRequest {}

message ServerInfo {
  string name = 1;
  string version = 2;
}
```

> [!TIP]
> Mark unary methods with `idempotency_level = NO_SIDE_EFFECTS` to enable `GET` request support.

Generate the gRPC stubs using `protoc` and the `grpc-java` plugin. The example project includes `protobuf-gradle-plugin` configured to do this automatically.

## Implementing the service

Extend the generated gRPC base class and implement the service methods:

```kotlin title="EchoService.kt"
package io.github.cgardev.example.demo

import io.github.cgardev.example.v1.EchoRequest
import io.github.cgardev.example.v1.EchoResponse
import io.github.cgardev.example.v1.EchoServiceGrpc
import io.github.cgardev.example.v1.GetServerInfoRequest
import io.github.cgardev.example.v1.ServerInfo
import io.grpc.stub.StreamObserver
import org.springframework.stereotype.Component

@Component
class EchoService : EchoServiceGrpc.EchoServiceImplBase() {

    override fun echo(request: EchoRequest, responseObserver: StreamObserver<EchoResponse>) {
        responseObserver.onNext(
            EchoResponse.newBuilder().setMessage("echo: ${request.message}").build()
        )
        responseObserver.onCompleted()
    }

    override fun getServerInfo(request: GetServerInfoRequest, responseObserver: StreamObserver<ServerInfo>) {
        responseObserver.onNext(
            ServerInfo.newBuilder()
                .setName("connect-kotlin-server")
                .setVersion("0.1.0")
                .build()
        )
        responseObserver.onCompleted()
    }
}
```

## Running with Spring Boot

The simplest path is the Spring Boot auto-configuration starter, which discovers your `BindableService` beans and hosts them over Connect automatically.

Add the dependency:

```kotlin title="build.gradle.kts"
dependencies {
    implementation("io.github.cgardev:connect-kotlin-server-spring-boot-autoconfigure:<commit>")
    implementation("org.springframework.boot:spring-boot-starter")
    // ... your gRPC stubs and proto dependencies
}
```

Configure the server in `application.properties`:

```properties title="application.properties"
spring.application.name=my-app
spring.main.web-application-type=none
connect.server.port=8080
```

No wiring code needed — the starter auto-configures the server and manages its lifecycle through Spring's `SmartLifecycle`. Boot the application:

```bash
./gradlew bootRun
```

The server binds Netty (not a servlet container) and hosts your services over Connect, Connect-streaming, and gRPC-Web on port 8080.

## Running without Spring

For standalone applications, use `ConnectServer` directly:

```kotlin title="Main.kt"
import io.github.cgardev.library.connect.ConnectServer
import io.github.cgardev.library.connect.config.ConnectServerConfig

fun main() {
    val server = ConnectServer(
        services = listOf(EchoService()),
        config = ConnectServerConfig(port = 8080),
    )
    server.start()
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
}
```

`ConnectServer` takes:
- `services`: a `List<BindableService>` — your gRPC implementations
- `interceptors`: optional `List<ServerInterceptor>` for the in-process gRPC pipeline
- `config`: a `ConnectServerConfig` with tuning options (`port`, `host`, `cors`, compression settings, etc.)

Call `start()` to bind Netty and host the in-process gRPC channel, and `close()` to shut down gracefully.

## Testing the server

Once the server is running, hit it with any HTTP client. The example server exposes two methods:

### Connect unary (POST with JSON)

```bash
curl -X POST http://localhost:8080/cgardev.example.v1.EchoService/Echo \
  -H 'Content-Type: application/json' \
  -d '{"message":"hello"}'
```

Response:

```json
{"message":"echo: hello"}
```

### GET idempotent method

Methods marked `idempotency_level = NO_SIDE_EFFECTS` can be called via GET:

```bash
curl 'http://localhost:8080/cgardev.example.v1.EchoService/GetServerInfo?encoding=json&message=%7B%7D'
```

Response:

```json
{"name":"connect-kotlin-server","version":"0.1.0"}
```

> [!NOTE]
> The `encoding=json` and `message=` query parameters are required for all GET requests. The `message` parameter carries the request body, URL-encoded.

## Running the example project

The repository includes a complete Spring Boot example at `:project:app-server-spring`. Clone the repository and run it:

```bash
git clone https://github.com/cgardev/connect-kotlin-server.git
cd connect-kotlin-server
./gradlew :project:app-server-spring:bootRun
```

The example exposes the `EchoService` with additional demo methods (streaming and error handling). Hitting the same endpoints above will work, and you can explore the source in `project/app-server-spring`.

## Next steps

- Learn about [Spring Boot configuration](/connect-kotlin-server/guides/spring-boot) for tuning the server.
- Explore [protocol support](/connect-kotlin-server/guides/protocols-and-codecs) for gRPC-Web, Connect streaming, and error handling.
- See the [API reference](/connect-kotlin-server/reference/api) for `ConnectServer` configuration and hooks.
