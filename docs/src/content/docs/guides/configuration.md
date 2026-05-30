---
title: "Configuration"
description: "Configure the Connect server via ConnectServerConfig or Spring properties under the connect.server namespace."
---

## Overview

The Connect server is configured through the `ConnectServerConfig` data class. When using Spring Boot with the auto-configuration starter, these settings are exposed under the `connect.server.*` property namespace and automatically mapped to the core configuration.

For non-Spring applications, construct a `ConnectServerConfig` directly and pass it to `ConnectServer`. For Spring applications, declare any `connect.server.*` properties and the auto-configuration will wire them for you.

## Configuration Options

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `host` | `String` | `"0.0.0.0"` | Address the embedded Netty server binds to. |
| `port` | `Int` | `8080` | Port the embedded Netty server binds to; `0` selects an ephemeral port. |
| `http1Enabled` | `Boolean` | `true` | Serve HTTP/1.1 on `port`. |
| `http2Enabled` | `Boolean` | `false` | Serve HTTP/2 cleartext (h2c) on `port`. Combined with `http1Enabled` the port negotiates between them; on its own it serves HTTP/2 only (prior knowledge). |
| `grpcEnabled` | `Boolean` | `false` | Start a native gRPC server (HTTP/2 cleartext) on `grpcPort`, exposing the same services over classic gRPC for server-to-server callers. |
| `grpcPort` | `Int` | `9090` | Port the native gRPC server binds to when `grpcEnabled`; `0` selects an ephemeral port. |
| `basePath` | `String` | `"/"` | Base path the dispatcher serves under. RPCs live at `<basePath><package>.<Service>/<Method>`; the default keeps paths equal to the gRPC full method name. |
| `requireProtocolVersion` | `Boolean` | `false` | Require `Connect-Protocol-Version: 1` header on Connect unary requests when `true`. |
| `getEnabled` | `Boolean` | `true` | Allow idempotent (`NO_SIDE_EFFECTS`) unary methods to be invoked via HTTP GET (see [GET idempotency](#get-idempotency)). |
| `compressMinBytes` | `Int` | `1024` | Only compress responses whose serialized size reaches this threshold (in bytes). |
| `readMaxBytes` | `Long` | `4194304` (4 MiB) | Maximum accepted (decompressed) request size, in bytes. Also bounds each decompressed envelope frame (see [Request size limits](#request-size-limits)). |
| `idleTimeoutMillis` | `Long` | `60000` (60 s) | Close a connection idle (no reads and no writes) for this long; `0` disables it (see [Idle timeout](#idle-timeout)). |
| `shutdownGraceMillis` | `Long` | `5000` | Grace period (in milliseconds) awaited when shutting down the in-process gRPC server/channel. |
| `cors.enabled` | `Boolean` | `true` | Master switch for CORS handling. |
| `cors.allowedOrigins` | `List<String>` | `["*"]` | Allowed origins; `*` matches any origin only when `allowCredentials` is `false` (see [CORS defaults](#cors-defaults)). |
| `cors.allowCredentials` | `Boolean` | `false` | Include `Access-Control-Allow-Credentials: true` in responses. When `true`, `*` no longer matches arbitrary origins. |
| `cors.allowPrivateNetwork` | `Boolean` | `false` | Include `Access-Control-Request-Private-Network` handling in CORS preflight. |
| `cors.maxAgeSeconds` | `Long` | `14400` (4 hours) | Value for `Access-Control-Max-Age` cache header. |

## Spring Boot Configuration

### application.yaml

```yaml
connect:
  server:
    enabled: true
    host: 0.0.0.0
    port: 8080
    basePath: /
    requireProtocolVersion: false
    getEnabled: true
    compressMinBytes: 1024
    readMaxBytes: 4194304
    idleTimeoutMillis: 60000
    shutdownGraceMillis: 5000
    cors:
      enabled: true
      allowedOrigins: [ "*" ]
      allowCredentials: false
      allowPrivateNetwork: false
      maxAgeSeconds: 14400
```

### application.properties

```properties
connect.server.enabled=true
connect.server.host=0.0.0.0
connect.server.port=8080
connect.server.basePath=/
connect.server.requireProtocolVersion=false
connect.server.getEnabled=true
connect.server.compressMinBytes=1024
connect.server.readMaxBytes=4194304
connect.server.idleTimeoutMillis=60000
connect.server.shutdownGraceMillis=5000
connect.server.cors.enabled=true
connect.server.cors.allowedOrigins[0]=*
connect.server.cors.allowCredentials=false
connect.server.cors.allowPrivateNetwork=false
connect.server.cors.maxAgeSeconds=14400
```

## Non-Spring Configuration

For a standalone application, construct `ConnectServerConfig` directly:

```kotlin
import io.github.cgardev.library.connect.ConnectServer
import io.github.cgardev.library.connect.config.ConnectServerConfig

fun main() {
    val config = ConnectServerConfig(
        host = "127.0.0.1",
        port = 9000,
        basePath = "/api/",
        getEnabled = true,
        compressMinBytes = 512,
        readMaxBytes = 8L * 1024 * 1024,
        shutdownGraceMillis = 10_000,
        cors = ConnectServerConfig.Cors(
            enabled = true,
            allowedOrigins = listOf("https://example.com"),
            allowCredentials = true,
            allowPrivateNetwork = false,
            maxAgeSeconds = 3600,
        ),
    )

    val server = ConnectServer(
        services = listOf(YourService()),
        config = config,
    )
    server.start()
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
}
```

## CORS Defaults

By default, the Connect server applies CORS handling suited for browser clients, **without credentials**:

- `cors.enabled = true` — CORS is active by default.
- `cors.allowedOrigins = ["*"]` — The wildcard `*` allows any origin. Because credentials are off, the server answers with `Access-Control-Allow-Origin: *`.
- `cors.allowCredentials = false` — Cookies and credentials are **not** permitted by default.
- `cors.allowPrivateNetwork = false` — Private network access (RFC 1918) is **not** advertised by default.
- `cors.maxAgeSeconds = 14400` (4 hours) — Preflight responses are cached for up to 4 hours.

To restrict CORS to specific origins, replace the wildcard with a list of allowed origins:

```yaml
connect:
  server:
    cors:
      allowedOrigins:
        - https://app.example.com
        - https://www.example.com
```

To disable CORS entirely:

```yaml
connect:
  server:
    cors:
      enabled: false
```

> [!CAUTION]
> When `allowCredentials` is `true`, the wildcard `*` no longer matches arbitrary origins: a request is allowed only when its `Origin` is **explicitly listed** in `allowedOrigins`. This prevents the server from reflecting an attacker-controlled origin together with `Access-Control-Allow-Credentials: true`. To support credentialed cross-origin requests, enumerate the exact origins:
>
> ```yaml
> connect:
>   server:
>     cors:
>       allowCredentials: true
>       allowedOrigins:
>         - https://app.example.com
> ```

## Compression

The server negotiates **gzip compression** automatically:

- Responses are only compressed if the client includes `Accept-Encoding: gzip` in the request.
- Compression is applied only to responses whose serialized size equals or exceeds `compressMinBytes` (default 1024 bytes).
- The response includes `Content-Encoding: gzip` and is sent with chunked transfer encoding.

Lower `compressMinBytes` to compress smaller payloads; raise it to reduce CPU overhead on large request volumes of small responses.

## GET Idempotency

When `getEnabled = true` (default), unary RPC methods marked with the protobuf option `idempotency_level = NO_SIDE_EFFECTS` can be invoked via HTTP GET:

```proto
rpc GetServerInfo(Empty) returns (ServerInfo) {
  option idempotency_level = NO_SIDE_EFFECTS;
}
```

GET requests encode the request message as a query parameter named `message` (protobuf binary or JSON, matching the `encoding` parameter). This is useful for status endpoints and other truly idempotent queries.

```bash
# GET request with JSON encoding
curl 'localhost:8080/cgardev.example.v1.EchoService/GetServerInfo?encoding=json&message=%7B%7D'
# => {"name":"connect-kotlin-server","version":"0.1.0"}
```

To disable GET entirely, set `getEnabled = false`.

> [!CAUTION]
> GET is only safe for methods explicitly marked `NO_SIDE_EFFECTS`. The server respects the proto contract; mismarking a method as idempotent when it has side effects will cause issues in production.

## Request Size Limits

The `readMaxBytes` limit (default 4 MiB) applies to the **decompressed** request body. It is enforced at three points, so an attacker cannot bypass it:

- The raw request body, before decompression.
- The total decompressed output — gzip decompression aborts with `resource_exhausted` once the output would exceed the limit, so a small "zip bomb" cannot expand to gigabytes.
- Each enveloped (streaming / gRPC-Web) frame — the length prefix is checked against the limit *before* the frame buffer is allocated, so a forged length cannot drive an unbounded allocation.

Requests exceeding the limit are rejected with `resource_exhausted` (HTTP 429).

For large file uploads or streaming workloads, increase `readMaxBytes`:

```yaml
connect:
  server:
    readMaxBytes: 100000000  # 100 MiB
```

## Idle Timeout

The `idleTimeoutMillis` limit (default 60 seconds) closes any connection that has neither read nor written for that long. This defends against slow-loris-style clients that open connections and hold them open without completing a request, exhausting the connection pool.

Tune it to the slowest legitimate idle period your clients need between frames (for example, a long-lived server stream with sparse messages), and set it to `0` to disable the timeout entirely:

```yaml
connect:
  server:
    idleTimeoutMillis: 120000  # 2 minutes
```

## Graceful Shutdown

When the server is closed, the in-process gRPC channel is shut down gracefully. The `shutdownGraceMillis` timeout (default 5 seconds) is the maximum time the server waits for in-flight RPCs to complete before forcing termination.

Long-running RPCs or streaming responses may need more time. Increase `shutdownGraceMillis` for workloads with slower handlers:

```yaml
connect:
  server:
    shutdownGraceMillis: 30000  # 30 seconds
```

## Protocol Version Negotiation

By default, `requireProtocolVersion = false` — the server accepts Connect unary requests regardless of whether they include the `Connect-Protocol-Version: 1` header.

Set `requireProtocolVersion = true` to enforce protocol version negotiation:

```yaml
connect:
  server:
    requireProtocolVersion: true
```

This is useful for ensuring clients are protocol-aware, though most clients already send the header.
