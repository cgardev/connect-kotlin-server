---
title: "Protocols & Codecs"
description: "Protocol selection via Content-Type, codec support (proto binary and protobuf-JSON), and method-type support matrix for Connect, Connect streaming, and gRPC-Web."
---

## Overview

connect-kotlin-server implements three wire protocols from the Connect family: **Connect unary** (simple non-enveloped), **Connect streaming** (enveloped), and **gRPC-Web** (enveloped). Each protocol carries messages encoded in one of two codecs: **proto binary** or **protobuf-JSON**. Protocol and codec are negotiated transparently from the `Content-Type` header on each request.

## Protocol Selection

The HTTP `Content-Type` header (both request and response) determines which protocol is used:

| Content-Type | Protocol | Message framing | Use case |
|---|---|---|---|
| `application/proto`, `application/x-protobuf`, `application/protobuf` | Connect unary | Non-enveloped | Unary RPC only; lowest overhead |
| `application/json` | Connect unary | Non-enveloped | Unary RPC only; human-readable |
| `application/connect+proto` | Connect streaming | 5-byte enveloped | Streaming over Connect protocol |
| `application/connect+json` | Connect streaming | 5-byte enveloped | Streaming with JSON codec |
| `application/grpc-web+proto` | gRPC-Web | 5-byte enveloped | Browser clients; streaming support |
| `application/grpc-web+json` | gRPC-Web | 5-byte enveloped | Browser clients with JSON codec |

The negotiation is case-insensitive and strips charset parameters. If no `Content-Type` is provided or it is unsupported, the request fails with HTTP 415 Unsupported Media Type.

## Codecs

Each request specifies its encoding through the codec inferred from the `Content-Type` (or the `encoding` parameter for GET requests).

### Proto Binary (`proto`)

Uses the standard Protocol Buffers binary wire format. Maps to `application/proto` and variants:

```kotlin
// Internally uses protobuf-java's standard serialization
message.toByteArray()          // serialize
prototype.parserForType.parseFrom(data)  // deserialize
```

Characteristics:
- **Size:** compact binary encoding
- **Speed:** fastest serialization/deserialization
- **Human-readable:** no; requires a `.proto` schema to inspect

### Protobuf-JSON (`json`)

Uses the canonical Protobuf JSON representation, backed by `protobuf-java-util`'s `JsonFormat`:

```kotlin
// Omits insignificant whitespace; resolves google.protobuf.Any via TypeRegistry
printer.print(message).toByteArray(StandardCharsets.UTF_8)
parser.merge(jsonString, builder)
```

Characteristics:
- **Size:** larger than binary; compresses well
- **Speed:** slower than binary
- **Human-readable:** yes; fully compatible with browser clients and JSON tooling
- **Any support:** resolves `google.protobuf.Any` fields if types are registered

## Message Framing

### Non-enveloped (Connect unary)

The message body is the serialized protobuf directly—no framing:

```
[message bytes]
```

Only supported for **unary** requests. Responses use the same format. Error responses are always JSON (see [error handling](/connect-kotlin-server/guides/error-handling)).

### Enveloped (Connect streaming & gRPC-Web)

Both Connect streaming and gRPC-Web use a 5-byte **length-prefixed envelope** format for each message:

```
[flags:1][length:4 big-endian][data:length]
```

- **flags** (1 byte):
  - Bit 0 (`0x01`): message data is compressed
  - Bit 1 (`0x02`): Connect streaming end-of-stream marker
  - Bit 7 (`0x80`): gRPC-Web trailer frame (status + headers)
- **length** (4 bytes, big-endian): byte count of data, **excludes the 5-byte header**
- **data** (variable): the serialized message or framing payload

#### Connect streaming end-of-stream

After the final message, Connect streaming sends a single envelope with the `FLAG_CONNECT_END_STREAM` flag set. The data is a JSON object with optional `error` and `metadata` fields (or empty if the call succeeded).

#### gRPC-Web trailers

After the final message, gRPC-Web sends a single envelope with the `FLAG_GRPC_WEB_TRAILER` flag. The data is an HTTP/1 header block (key-value pairs) containing the gRPC status, message, and any error details.

## Method Support Matrix

| RPC type | Connect unary | Connect streaming | gRPC-Web |
|---|:---:|:---:|:---:|
| Unary | ✅ | ✅ | ✅ |
| Server streaming | — | ✅ | ✅ |
| Client streaming / bidirectional | — | — | — |

**Unary** methods work on all three protocols; the protocol is selected by `Content-Type`.

**Server streaming** requires an enveloped protocol (Connect streaming or gRPC-Web); the non-enveloped Connect protocol does not support streaming.

**Client streaming and bidirectional** RPC are not supported. These require full HTTP/2 support, which the embedded Netty server does not provide (HTTP/1.1 only).

## GET for Idempotent Unary Methods

Unary methods marked with `idempotency_level = NO_SIDE_EFFECTS` in their `.proto` definition can be invoked via HTTP GET:

```bash
curl 'localhost:8080/package.Service/Method?encoding=json&message=%7B%7D'
```

Query parameters:

| Name | Required | Purpose |
|---|---|---|
| `encoding` | Yes | Codec: `json` or `proto` |
| `message` | Yes | URL-safe base64 or UTF-8 JSON string (see `base64` parameter) |
| `base64` | No | If `1`, decode `message` as URL-safe base64 instead of UTF-8 JSON |
| `compression` | No | Compression codec (e.g., `gzip`); only for proto-encoded messages |
| `connect` | No if `requireProtocolVersion` is false | Version check: must be `v1` |

GET is disabled by default; enable it with the `connect.server.get-enabled` property or `ConnectServerConfig.getEnabled = true`.

> [!TIP]
> GET is useful for browser bookmarks and CDN caching of read-only operations. Since the message is passed in the query string, it is visible in logs and browser history — do not use for sensitive data.

## Example: Content Negotiation

Given a POST to `POST /myapp.v1.EchoService/Echo`:

```http
Content-Type: application/connect+proto
```

→ Protocol: **Connect streaming**, Codec: **proto binary**, Framing: **enveloped**

→ Request and response messages are framed with the 5-byte envelope, suitable for streaming.

---

```http
Content-Type: application/json
```

→ Protocol: **Connect unary**, Codec: **protobuf-JSON**, Framing: **non-enveloped**

→ Request and response bodies are raw JSON; only unary methods are allowed.

---

```http
Content-Type: application/grpc-web+json
```

→ Protocol: **gRPC-Web**, Codec: **protobuf-JSON**, Framing: **enveloped**

→ Response includes a gRPC-Web trailer frame; suitable for browser clients.

> [!NOTE]
> The response `Content-Type` always matches the request protocol (though codec preference may be influenced by client Accept headers). See the Connect spec and gRPC-Web spec for full details on header semantics.
