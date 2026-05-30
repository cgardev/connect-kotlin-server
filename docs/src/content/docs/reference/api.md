---
title: "API Reference"
description: "Public API types, methods, and properties for the Connect Kotlin server."
---

## Core library

The core library (`io.github.cgardev:connect-kotlin-server`) is **framework-agnostic** and contains zero Spring or Servlet API dependencies.

### `ConnectServer`

**Package:** `io.github.cgardev.library.connect`

The primary entry point. Hosts `BindableService`s on an in-process gRPC channel and serves them over Connect, Connect-streaming, and gRPC-Web via an embedded Netty HTTP server.

#### Constructor

```kotlin
ConnectServer(
    services: List<BindableService>,
    interceptors: List<ServerInterceptor> = emptyList(),
    config: ConnectServerConfig = ConnectServerConfig(),
)
```

- **`services`** — A list of gRPC services (any `BindableService`). Required.
- **`interceptors`** — Optional list of gRPC `ServerInterceptor`s to apply to the in-process channel, exactly as a traditional gRPC server would. Defaults to empty.
- **`config`** — A `ConnectServerConfig` with server parameters. Defaults to a config with sensible defaults (port 8080, CORS enabled).

#### Properties

- **`config: ConnectServerConfig`** — The configuration object passed at construction; readable for inspection.
- **`registry: ConnectMethodRegistry`** — The internal method registry built from the services at construction; contains introspection about the available methods.
- **`boundPort: Int`** — The actual port the Netty server is listening on. Valid only after `start()` completes; resolves ephemeral port 0 to its real port.

#### Methods

- **`start(): Unit`** — Starts the in-process gRPC channel and binds the Netty HTTP server. Must be called before serving requests.
- **`close(): Unit`** — Shuts down the Netty server gracefully (respecting `shutdownGraceMillis`) and closes the gRPC channel. Implements `AutoCloseable`; safe to call multiple times.

### `ConnectServerConfig`

**Package:** `io.github.cgardev.library.connect.config`

Plain Kotlin data class holding server configuration. No framework annotations — designed to be mapped onto from any configuration source.

```kotlin
data class ConnectServerConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val basePath: String = "/",
    val requireProtocolVersion: Boolean = false,
    val getEnabled: Boolean = true,
    val compressMinBytes: Int = 1024,
    val readMaxBytes: Long = 4L * 1024 * 1024,
    val shutdownGraceMillis: Long = 5_000,
    val cors: Cors = Cors(),
)
```

#### Properties

- **`host`** — Address the Netty server binds to. Default: `"0.0.0.0"`.
- **`port`** — Port to bind to; `0` selects an ephemeral port. Default: `8080`.
- **`basePath`** — URL path prefix under which RPCs are served. Full RPC paths are `<basePath><package>.<Service>/<Method>`; the default root `/` keeps paths equal to the gRPC full method name. Default: `"/"`.
- **`requireProtocolVersion`** — If true, Connect unary requests must carry the `Connect-Protocol-Version: 1` header. Default: `false`.
- **`getEnabled`** — Allow idempotent (`NO_SIDE_EFFECTS`) unary methods to be invoked via HTTP GET. Default: `true`.
- **`compressMinBytes`** — Only compress responses whose serialized size reaches this threshold (in bytes). Default: `1024`.
- **`readMaxBytes`** — Maximum accepted (decompressed) request size, in bytes. Default: 4 MiB.
- **`shutdownGraceMillis`** — Grace period awaited when shutting down the in-process gRPC server/channel. Default: `5000`.
- **`cors`** — A nested `Cors` object controlling CORS behavior.

#### `ConnectServerConfig.Cors`

```kotlin
data class Cors(
    val enabled: Boolean = true,
    val allowedOrigins: List<String> = listOf("*"),
    val allowCredentials: Boolean = true,
    val allowPrivateNetwork: Boolean = true,
    val maxAgeSeconds: Long = 4 * 60 * 60,
)
```

- **`enabled`** — Master switch for CORS. Default: `true`.
- **`allowedOrigins`** — List of allowed origins. `"*"` echoes the request origin (required when credentials are allowed). Default: `listOf("*")`.
- **`allowCredentials`** — Allow credentials in CORS requests. Default: `true`.
- **`allowPrivateNetwork`** — Allow requests from private IP ranges. Default: `true`.
- **`maxAgeSeconds`** — Max age for preflight response caching. Default: `14400` (4 hours).

### `ConnectCode`

**Package:** `io.github.cgardev.library.connect.error`

Enum of the canonical Connect error codes. Each code carries three on-the-wire representations: a numeric value (gRPC-Web trailer), a lowercase `snake_case` name (Connect unary JSON and Connect end-of-stream), and the HTTP status used for Connect unary error responses.

```kotlin
enum class ConnectCode(
    val number: Int,
    val wireName: String,
    val httpStatus: Int,
)
```

#### Enum values

| Code | Number | Wire name | HTTP status |
|------|--------|-----------|-------------|
| `CANCELED` | 1 | `canceled` | 499 |
| `UNKNOWN` | 2 | `unknown` | 500 |
| `INVALID_ARGUMENT` | 3 | `invalid_argument` | 400 |
| `DEADLINE_EXCEEDED` | 4 | `deadline_exceeded` | 504 |
| `NOT_FOUND` | 5 | `not_found` | 404 |
| `ALREADY_EXISTS` | 6 | `already_exists` | 409 |
| `PERMISSION_DENIED` | 7 | `permission_denied` | 403 |
| `RESOURCE_EXHAUSTED` | 8 | `resource_exhausted` | 429 |
| `FAILED_PRECONDITION` | 9 | `failed_precondition` | 400 |
| `ABORTED` | 10 | `aborted` | 409 |
| `OUT_OF_RANGE` | 11 | `out_of_range` | 400 |
| `UNIMPLEMENTED` | 12 | `unimplemented` | 501 |
| `INTERNAL` | 13 | `internal` | 500 |
| `UNAVAILABLE` | 14 | `unavailable` | 503 |
| `DATA_LOSS` | 15 | `data_loss` | 500 |
| `UNAUTHENTICATED` | 16 | `unauthenticated` | 401 |

#### Properties

- **`number: Int`** — The numeric code (used in gRPC-Web trailers).
- **`wireName: String`** — The lowercase `snake_case` name (used in Connect JSON and end-of-stream).
- **`httpStatus: Int`** — The HTTP status code for Connect unary error responses.
- **`grpcName: String`** — Alias for the enum name; the uppercase form used in gRPC status names and Connect end-of-stream messages.

#### Static methods

- **`fromNumber(number: Int): ConnectCode`** — Resolve a code from its numeric value; defaults to `UNKNOWN` if not found.
- **`fromWireName(wireName: String): ConnectCode`** — Resolve a code from its lowercase wire name; defaults to `UNKNOWN` if not found.
- **`fromGrpc(code: Status.Code): ConnectCode`** — Map a gRPC status code to the equivalent Connect code. `OK` resolves to `UNKNOWN` (it has no error representation in Connect).

### `ConnectException`

**Package:** `io.github.cgardev.library.connect.error`

The server-side error type. Handlers and the dispatcher throw this to produce a Connect error response (unary JSON body, Connect end-of-stream message, or gRPC-Web trailer frame depending on the protocol).

```kotlin
class ConnectException(
    val code: ConnectCode,
    override val message: String = "",
    val details: List<ConnectErrorDetail> = emptyList(),
    cause: Throwable? = null,
) : RuntimeException(message, cause)
```

#### Constructor parameters

- **`code`** — The Connect error code. Required.
- **`message`** — Human-readable error message. Default: empty string.
- **`details`** — List of structured error details (protobuf `Any` messages). Default: empty list.
- **`cause`** — Optional wrapped exception (for debugging). Default: `null`.

### `ConnectErrorDetail`

**Package:** `io.github.cgardev.library.connect.error`

A single structured error detail, carrying an arbitrary protobuf message serialized as a `google.protobuf.Any`.

```kotlin
data class ConnectErrorDetail(val any: ProtoAny)
```

Serialized to the `details` array of the Connect error JSON as `{ "type": ..., "value": <base64> }`.

## Spring integration

The Spring integration modules are **optional**. Both re-export the core types and add Spring-specific wiring.

### `ConnectServerLifecycle`

**Package:** `io.github.cgardev.library.connect.spring`

Binds the spring-free `ConnectServer` to Spring's `SmartLifecycle`, so the server starts and stops with the application context.

```kotlin
class ConnectServerLifecycle(private val connectServer: ConnectServer) : SmartLifecycle
```

#### Behavior

- **Starts on context startup** — `isAutoStartup()` returns `true`.
- **Runs in a late phase** — `getPhase()` returns `Int.MAX_VALUE`, ensuring the server starts last (after all other beans are ready) and stops first (before other beans shut down).
- **Graceful shutdown** — Delegates to `connectServer.close()`, which respects the configured grace period.
- **Thread-safe** — Uses `AtomicBoolean` to guard against concurrent start/stop calls.

### `ConnectServerProperties`

**Package:** `io.github.cgardev.library.connect.spring`

Spring Boot `@ConfigurationProperties` bound to the `connect.server.*` namespace. Kept separate from the core's plain `ConnectServerConfig` so the library stays free of Spring annotations.

```kotlin
@ConfigurationProperties(prefix = "connect.server")
data class ConnectServerProperties(
    val enabled: Boolean = true,
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val basePath: String = "/",
    val requireProtocolVersion: Boolean = false,
    val getEnabled: Boolean = true,
    val compressMinBytes: Int = 1024,
    val readMaxBytes: Long = 4L * 1024 * 1024,
    val shutdownGraceMillis: Long = 5_000,
    val cors: Cors = Cors(),
)
```

#### Properties

- **`enabled`** — Master switch for the auto-configuration. Default: `true`. Set to `false` to disable the auto-configuration entirely.
- **`host`, `port`, `basePath`, `requireProtocolVersion`, `getEnabled`, `compressMinBytes`, `readMaxBytes`, `shutdownGraceMillis`** — Mirror the core `ConnectServerConfig` properties; see above.
- **`cors`** — Nested `Cors` object with the same structure as the core type.

#### Methods

- **`toConfig(): ConnectServerConfig`** — Maps these Spring properties onto the core's plain configuration type. Used internally by the auto-configuration.

### `ConnectServerAutoConfiguration`

**Package:** `io.github.cgardev.library.connect.spring.autoconfigure`

The Spring Boot auto-configuration starter. Declares the `ConnectServer` and `ConnectServerLifecycle` beans, discovering all `BindableService` and `ServerInterceptor` beans in the context.

```kotlin
@AutoConfiguration
@ConditionalOnClass(BindableService::class)
@ConditionalOnProperty(prefix = "connect.server", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ConnectServerProperties::class)
class ConnectServerAutoConfiguration
```

#### Beans provided

- **`connectServer(services, interceptors, properties): ConnectServer`** — Declared `@ConditionalOnMissingBean`. Collects all `BindableService` beans (in order), all `ServerInterceptor` beans (in order), and builds a `ConnectServer` from the bound `ConnectServerProperties`. Uses Spring's `ObjectProvider` for lazy, optional discovery.

- **`connectServerLifecycle(connectServer): ConnectServerLifecycle`** — Declared `@ConditionalOnMissingBean`. Wraps the `ConnectServer` in a lifecycle bean for automatic startup/shutdown.

#### Activation conditions

- Active only if `BindableService` is on the classpath.
- Active only if the property `connect.server.enabled` is `true` (or absent). Set to `false` to disable.
- `ConnectServerProperties` are bound and validated.

> [!NOTE]
> **Zero wiring required.** Simply declare your `BindableService` beans as `@Component` or `@Bean`, and the auto-configuration discovers and serves them automatically.
