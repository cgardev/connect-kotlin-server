# connect-kotlin-server

Serve the [Connect protocol](https://connectrpc.com/docs/protocol) — plus gRPC-Web
and Connect streaming — **natively from the JVM**, directly on top of your existing
`grpc-java` services. No sidecar, no Go transcoding proxy.

[![CI](https://github.com/cgardev/connect-kotlin-server/actions/workflows/ci.yml/badge.svg)](https://github.com/cgardev/connect-kotlin-server/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Status: alpha](https://img.shields.io/badge/status-alpha-orange.svg)](#disclaimer)

> [!WARNING]
> **Alpha.** This project is in early, active development. The public API, wire
> behaviour and module layout may change without notice between releases, and it
> has not been hardened for production use. Pin an exact version and review the
> release notes before upgrading. See the [disclaimer](#disclaimer).

## Why

[`connect-kotlin`](https://github.com/connectrpc/connect-kotlin) is **client-only** —
there is no server-side Connect implementation for Kotlin/Java. The common workaround
is to run a separate Go proxy (e.g. [Vanguard](https://github.com/connectrpc/vanguard-go))
in front of a gRPC backend to transcode Connect/gRPC-Web into gRPC.

`connect-kotlin-server` removes that hop: it hosts your `io.grpc.BindableService`s on an
**in-process gRPC channel** and serves them over the Connect protocols through an
**embedded Netty server**, invoking your handlers in the same JVM.

## Features

- **Protocols:** Connect (unary, non-enveloped), Connect streaming, and gRPC-Web.
- **Codecs:** Protobuf binary (`application/proto`) and Protobuf-JSON (`application/json`).
- **Transport:** embedded Netty HTTP/1.1; requests run on virtual threads off the event
  loop, server-streaming is delivered via chunked transfer.
- **Error model:** the full Connect error JSON (`code` / `message` / `details`), gRPC-Web
  trailers, and Connect end-of-stream — mapped from `io.grpc.Status`, verified against the
  `connect-go` reference.
- **Extras:** `GET` for idempotent (`NO_SIDE_EFFECTS`) unary methods, gzip, permissive CORS
  for browser clients, request metadata ↔ gRPC `Metadata`, and a liveness probe.
- **No framework lock-in:** the core library has **zero dependency on Spring or the Servlet
  API**. The only Spring code lives in the example app.

## Requirements

- JDK 24+
- gRPC services generated with `grpc-java` (any `BindableService`)

## Installation

```kotlin
dependencies {
    implementation("io.github.cgardev:connect-kotlin-server:0.1.0")
}
```

## Quick start

`ConnectServer` is the framework-agnostic entry point. Hand it your services, start it,
and it serves them over Connect/gRPC-Web:

```kotlin
import io.github.cgardev.library.connect.ConnectServer
import io.github.cgardev.library.connect.config.ConnectServerConfig

fun main() {
    val server = ConnectServer(
        services = listOf(EchoService()),                 // any io.grpc.BindableService
        config = ConnectServerConfig(port = 8080),
    )
    server.start()                                         // binds Netty, hosts the in-process gRPC channel
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
}
```

### With Spring Boot (no servlet container)

The Connect server brings its own Netty transport, so the application does not need an
embedded servlet container (`spring.main.web-application-type=none`). Wire it as a bean:

```kotlin
@Configuration
class ConnectServerConfiguration {
    @Bean(destroyMethod = "close")
    fun connectServer(
        services: ObjectProvider<BindableService>,
        interceptors: ObjectProvider<ServerInterceptor>,
        @Value("\${connect.server.port:8080}") port: Int,
    ): ConnectServer = ConnectServer(
        services = services.orderedStream().toList(),
        interceptors = interceptors.orderedStream().toList(),
        config = ConnectServerConfig(port = port),
    ).apply { start() }
}
```

Any `BindableService` bean is discovered automatically; `ServerInterceptor` beans are
applied to the in-process pipeline, exactly as a real gRPC server would.

## Trying the example

The `:project:app-server-spring` module exposes a demo `EchoService`
(`cgardev.example.v1.EchoService`):

```bash
./gradlew :project:app-server-spring:bootRun        # serves on :8080

# Connect unary, JSON
curl -X POST localhost:8080/cgardev.example.v1.EchoService/Echo \
  -H 'Content-Type: application/json' -d '{"message":"hi"}'
# => {"message":"echo: hi"}

# Idempotent unary over GET
curl 'localhost:8080/cgardev.example.v1.EchoService/GetServerInfo?encoding=json&message=%7B%7D'
# => {"name":"connect-kotlin-server","version":"0.1.0"}
```

## Protocol support

| RPC type           | Connect | gRPC-Web | Connect streaming |
|--------------------|:-------:|:--------:|:-----------------:|
| Unary              |   ✅    |    ✅    |        ✅         |
| Server streaming   |   —     |    ✅    |        ✅         |
| Client / bidi      |   —     |    —     |        —          |

`GET` is supported for unary methods marked `idempotency_level = NO_SIDE_EFFECTS`.

## Building

```bash
./gradlew build      # compile + test every module
```

## Project layout

```
project/lib-connect-server/   The library (published artifact). Spring-free, Servlet-free.
project/app-server-spring/    Runnable example: a demo gRPC service served over Connect.
build-logic/                  Gradle convention plugins.
```

## Publishing

The library publishes via `maven-publish` to GitHub Packages out of the box (CI uses the
repository `GITHUB_TOKEN`). The group is `io.github.cgardev`, whose Maven Central namespace
is verified simply by proving ownership of the `cgardev` GitHub account through the Sonatype
Central Portal — no custom domain required. Maven Central additionally needs a GPG signing
key provided through the `SIGNING_KEY` / `SIGNING_PASSWORD` environment variables.
See [`.github/workflows/publish.yml`](.github/workflows/publish.yml).

## Disclaimer

This is **alpha software, provided "as is"**, without warranty of any kind, express or
implied, including but not limited to the warranties of merchantability and fitness for a
particular purpose. To the maximum extent permitted by applicable law, the authors and
contributors shall **not be liable** for any claim, damages, data loss or other liability,
whether in an action of contract, tort or otherwise, arising from, out of or in connection
with the software or its use. **You use it at your own risk.** See Sections 7 and 8 of the
[Apache License 2.0](LICENSE) for the full terms.

## License

[Apache License 2.0](LICENSE). Not affiliated with or endorsed by the Connect project.
