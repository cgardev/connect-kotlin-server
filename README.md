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

📖 **Documentation:** **<https://cgardev.github.io/connect-kotlin-server/>** — getting started,
architecture, protocols, Spring Boot, configuration, error handling and API reference.
(Sources in [`docs/`](docs/), an Astro Starlight site.)

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
- **Transport:** embedded Netty serving HTTP/1.1 and, optionally, HTTP/2 cleartext (h2c) on
  the same port; requests run on virtual threads off the event loop and server-streaming is
  delivered via chunked transfer. An optional native gRPC port can expose the same services
  over classic gRPC for server-to-server callers.
- **Error model:** the full Connect error JSON (`code` / `message` / `details`), gRPC-Web
  trailers, and Connect end-of-stream — mapped from `io.grpc.Status`, verified against the
  `connect-go` reference.
- **Extras:** `GET` for idempotent (`NO_SIDE_EFFECTS`) unary methods, gzip (with a bounded,
  zip-bomb-safe decoder), CORS for browser clients (credentials off by default), request
  metadata ↔ gRPC `Metadata`, an idle-connection timeout, and a liveness probe.
- **No framework lock-in:** the core library has **zero dependency on Spring or the Servlet
  API**. The only Spring code lives in the example app.

## Requirements

- JDK 24+
- gRPC services generated with `grpc-java` (any `BindableService`)

## Installation

> [!IMPORTANT]
> There are **no published releases yet**. While the project is in alpha, it is
> versioned **by commit** — depend on a specific commit and treat every commit as
> potentially breaking (see [Versioning & compatibility](#versioning--compatibility)).

Pull a specific commit through [JitPack](https://jitpack.io):

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    // Replace <commit> with the exact short commit SHA you want to pin to.
    implementation("com.github.cgardev:connect-kotlin-server:<commit>")
}
```

Or build it locally and depend on the snapshot:

```bash
git clone https://github.com/cgardev/connect-kotlin-server.git
cd connect-kotlin-server && ./gradlew :project:lib-connect-server:publishToMavenLocal
# then add mavenLocal() and io.github.cgardev:connect-kotlin-server:0.0.0-SNAPSHOT
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

### With Spring Boot

The Spring support is split into two modules, following Spring's own `*-autoconfigure`
convention — pick the one that matches how much wiring you want:

```kotlin
dependencies {
    // Zero wiring: auto-configures the server and registers its SmartLifecycle.
    implementation("io.github.cgardev:connect-kotlin-server-spring-boot-autoconfigure:<commit>")

    // — or — just the Spring components (ConnectServerLifecycle, ConnectServerProperties),
    // for when you want to declare the beans yourself:
    // implementation("io.github.cgardev:connect-kotlin-server-spring:<commit>")
}
```

With the auto-configuration starter you write **no wiring at all**: it builds the server
from your beans and manages it through a Spring `SmartLifecycle` (started with the context,
shut down gracefully on close). The Connect server brings its own Netty transport, so the
application needs no servlet container (`spring.main.web-application-type=none`).

```kotlin
@Component
class EchoService : EchoServiceGrpc.EchoServiceImplBase() { /* ... */ }
```

Any `BindableService` bean is discovered and served; `ServerInterceptor` beans are applied
to the in-process pipeline, exactly as a real gRPC server would. Everything is tunable under
the `connect.server.*` properties (`port`, `cors.*`, `compress-min-bytes`, …).

For a non-Spring application, build a [`ConnectServer`](#quick-start) directly as shown above.

## Transport modes

Each transport has an explicit enable, so you can serve browsers, server-to-server callers,
or both, in whatever combination you need:

| Property | Default | Effect |
|----------|:-------:|--------|
| `connect.server.http1-enabled` | `true`  | Serve HTTP/1.1 on `connect.server.port`. |
| `connect.server.http2-enabled` | `false` | Serve HTTP/2 cleartext (h2c) on the same port. |
| `connect.server.grpc-enabled`  | `false` | Start a native gRPC server (h2c) on `connect.server.grpc-port`. |

Common setups:

- **HTTP/1.1 + HTTP/2 on one port (h2c):** `http1-enabled=true`, `http2-enabled=true` — browsers
  use HTTP/1.1, server-to-server callers negotiate HTTP/2 (via the `Upgrade` handshake or the
  HTTP/2 preface).
- **HTTP/2 only:** `http1-enabled=false`, `http2-enabled=true` — pure h2c (prior knowledge);
  plain HTTP/1.1 connections are rejected.
- **Two ports (web + gRPC):** keep `http1-enabled=true` and set `grpc-enabled=true` — the
  Connect/gRPC-Web server serves browsers on `port` while a native gRPC server serves
  server-to-server callers over classic gRPC on `grpc-port`.

With the plain `ConnectServer`, set `http1Enabled` / `http2Enabled` / `grpcEnabled` /
`grpcPort` on `ConnectServerConfig`; `boundPort` and `grpcBoundPort` report the bound ports.

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

The JVM tests in `app-server-spring` already drive the server over Connect, Connect
streaming and gRPC-Web. In addition, `tools/e2e-connect-web` is an end-to-end suite that
hits a running instance with the real [`@connectrpc/connect-web`](https://www.npmjs.com/package/@connectrpc/connect-web)
client — the same library a browser uses — across both protocols and both the proto-binary
and JSON encodings. It builds and launches the server itself:

```bash
cd tools/e2e-connect-web && pnpm install && pnpm test
```

## Project layout

```
project/lib-connect-server/                          The core library. Spring-free, Servlet-free.
project/lib-connect-server-spring/                   Spring components: SmartLifecycle + properties.
project/lib-connect-server-spring-boot-autoconfigure/ Auto-configuration starter (depends on the above).
project/app-server-spring/                           Runnable example: a demo service served over Connect.
build-logic/                                         Gradle convention plugins.
```

## Versioning & compatibility

During alpha there are **no published releases and no version numbers** — the commit
**is** the version. Pin your dependency to an exact commit and upgrade deliberately:

- **No compatibility is guaranteed.** Source, binary and wire behaviour may change between
  any two commits, without deprecation cycles or migration notes.
- The Gradle version stays at `0.0.0-SNAPSHOT`; it is not a release.
- Once the API settles, this project will adopt semantic versioning and tagged releases.

## Publishing

There are no tagged releases yet, so consumers use JitPack by commit (see
[Installation](#installation)); JitPack builds the requested commit per
[`jitpack.yml`](jitpack.yml).

The build is also set up to publish via `maven-publish` once releases begin: to GitHub
Packages out of the box (CI uses the repository `GITHUB_TOKEN`), or to Maven Central under
the `io.github.cgardev` group. GitHub Packages requires consumers to authenticate, so Maven
Central is the path for unauthenticated consumption.

Maven Central publishing goes through the [Sonatype Central Portal](https://central.sonatype.com),
which requires, as a one-time setup:

1. A Central Portal account and verification of the `io.github.cgardev` namespace, done by
   proving ownership of the `cgardev` GitHub account (no custom domain needed).
2. A GPG signing key, with the public key uploaded to a keyserver.
3. A Central Portal user token, plus the GPG key, added as repository secrets:
   `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`, `SIGNING_KEY`, `SIGNING_PASSWORD`.

The `Publish` workflow then uploads the signed artifacts to a Central Portal staging
deployment, which is released from the portal (or automatically, if the namespace uses
automatic publishing). Maven Central rejects `SNAPSHOT` versions, so the publish job uses the
short commit hash as the release version. The Maven Central step is skipped until the
`CENTRAL_USERNAME` secret exists, so GitHub Packages publishing keeps working beforehand.
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
