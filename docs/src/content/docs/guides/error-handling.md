---
title: "Error Handling"
description: "Understand how gRPC Status errors become Connect/gRPC-Web errors with code, message, and details."
---

## Overview

When your gRPC handler throws a `StatusRuntimeException` or `StatusException`, the connect-kotlin-server dispatcher catches it and translates it into a Connect error response. The translation preserves the error code, message, and any structured `google.rpc.Status` details (such as `ErrorInfo`), then serializes them according to the protocol in use: unary JSON, Connect streaming end-of-stream envelope, or gRPC-Web trailer frame.

## Error Code Mapping

The connect-kotlin-server error model defines 16 canonical codes, each with three on-the-wire representations:
- **number** — the numeric code value, used in gRPC-Web trailers
- **wireName** — the lowercase `snake_case` name, used in Connect unary JSON and Connect end-of-stream messages
- **httpStatus** — the HTTP status code returned for Connect unary error responses

### Code Reference

| Enum | Code | Wire Name | HTTP Status |
|------|:----:|-----------|:-----------:|
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

The mapping from gRPC `io.grpc.Status.Code` to `ConnectCode` is one-to-one (with the exception of `OK`, which has no error representation and resolves to `UNKNOWN`).

## Throwing Errors from Handlers

Use the standard gRPC error model: throw a `StatusRuntimeException` with an `io.grpc.Status`:

```kotlin
import io.grpc.Status
import io.grpc.stub.StreamObserver

override fun yourMethod(request: YourRequest, responseObserver: StreamObserver<YourResponse>) {
    throw Status.INVALID_ARGUMENT
        .withDescription("Request must have a non-empty name")
        .asRuntimeException()
}
```

If you need to include structured error details (e.g., `ErrorInfo`, `BadRequest`), build a `google.rpc.Status` and convert it:

```kotlin
import com.google.protobuf.Any
import com.google.rpc.ErrorInfo
import io.grpc.protobuf.StatusProto
import io.grpc.stub.StreamObserver

override fun fail(request: FailRequest, responseObserver: StreamObserver<YourResponse>) {
    val errorInfo = ErrorInfo.newBuilder()
        .setReason("VALIDATION_FAILED")
        .setDomain("example.com")
        .putMetadata("field", "email")
        .putMetadata("constraint", "invalid_format")
        .build()
    
    val status = com.google.rpc.Status.newBuilder()
        .setCode(io.grpc.Status.Code.INVALID_ARGUMENT.value())
        .setMessage("validation failed: email is invalid")
        .addDetails(Any.pack(errorInfo))
        .build()
    
    responseObserver.onError(StatusProto.toStatusRuntimeException(status))
}
```

> [!NOTE]
> You can attach multiple details of different types by calling `addDetails()` multiple times. All details must be protobuf messages packed as `google.protobuf.Any`.

## Error Response Formats

### Unary JSON

A Connect unary error response has HTTP status matching the error's `httpStatus` and `Content-Type: application/json`. The body is a JSON object:

```json
{
  "code": "invalid_argument",
  "message": "validation failed: email is invalid",
  "details": [
    {
      "type": "google.rpc.ErrorInfo",
      "value": "<base64-encoded protobuf>",
      "debug": { "reason": "VALIDATION_FAILED", "domain": "example.com", "metadata": { "field": "email", "constraint": "invalid_format" } }
    }
  ]
}
```

Fields:
- **code** — lowercase wire name (always present)
- **message** — optional; omitted if empty
- **details** — optional; omitted if empty
  - **type** — fully qualified protobuf message name extracted from `google.protobuf.Any.typeUrl`
  - **value** — base64 (no padding) of the serialized protobuf message
  - **debug** — optional; human-readable JSON rendering of the detail when its type is known to the server's type registry

### Connect Streaming End-of-Stream

When a server-streaming or client-streaming handler fails, the error is sent as a Connect end-of-stream envelope: a frame with flag `0x02` and a JSON payload:

```json
{
  "error": {
    "code": "invalid_argument",
    "message": "validation failed",
    "details": [...]
  },
  "metadata": {
    "header1": ["value1"],
    "header2": ["value2"]
  }
}
```

The `error` object has the same shape as unary errors. The `metadata` object is optional and carries trailing headers. The `error` key is omitted entirely on success.

### gRPC-Web Trailer Frame

For gRPC-Web responses, errors are encoded in the trailer frame (a raw HTTP/1.1 header block with CRLF line endings):

```
grpc-status: 3
grpc-message: validation%20failed
grpc-status-details-bin: <base64-no-pad encoded google.rpc.Status>
```

The trailer contains:
- **grpc-status** — numeric code (1–16)
- **grpc-message** — optional; omitted if empty; percent-encoded per gRPC spec (escapes bytes < 0x20, > 0x7E, and `%`)
- **grpc-status-details-bin** — optional; omitted if no details; base64 (no padding) of a serialized `google.rpc.Status` protobuf

The `google.rpc.Status` in the trailer contains the code, message, and details in their native protobuf form.

## Error Handling in connect-web Clients

When a connect-web client receives an error, it is wrapped in a `ConnectError`:

```typescript
import { ConnectError, Code } from '@connectrpc/connect';

try {
  await client.fail({ reason: 'boom' });
} catch (err) {
  if (err instanceof ConnectError) {
    console.log(err.code);      // Code.InvalidArgument
    console.log(err.message);   // "validation failed: email is invalid"
    console.log(err.details);   // Array of ConnectErrorDetail objects
  }
}
```

Each detail in `err.details` is an object with:
- **type** — fully qualified message name (e.g., `"google.rpc.ErrorInfo"`)
- **value** — raw bytes of the serialized message, typically decoded and inspected by the application

## Example: Handler with ErrorInfo

Here is a complete Kotlin handler that throws an error with structured details:

```kotlin
import com.google.protobuf.Any
import com.google.rpc.ErrorInfo
import io.grpc.protobuf.StatusProto
import io.grpc.stub.StreamObserver

class MyService : MyServiceGrpc.MyServiceImplBase() {
    override fun validateEmail(request: ValidateRequest, responseObserver: StreamObserver<ValidateResponse>) {
        if (!request.email.contains("@")) {
            val errorInfo = ErrorInfo.newBuilder()
                .setReason("INVALID_EMAIL")
                .setDomain("validation.example.com")
                .putMetadata("field", "email")
                .putMetadata("provided", request.email)
                .build()
            
            val status = com.google.rpc.Status.newBuilder()
                .setCode(io.grpc.Status.Code.INVALID_ARGUMENT.value())
                .setMessage("email must contain @")
                .addDetails(Any.pack(errorInfo))
                .build()
            
            responseObserver.onError(StatusProto.toStatusRuntimeException(status))
            return
        }
        
        responseObserver.onNext(ValidateResponse.newBuilder().setValid(true).build())
        responseObserver.onCompleted()
    }
}
```

When invoked with `{"email": "invalid"}` over Connect/JSON:
- HTTP status: `400`
- Content-Type: `application/json`
- Body:

```json
{
  "code": "invalid_argument",
  "message": "email must contain @",
  "details": [
    {
      "type": "google.rpc.ErrorInfo",
      "value": "CghJTlZBTElEX0VNQUlMEhd2YWxpZGF0aW9uLmV4YW1wbGUuY29tGhIKBWZpZWxkEgVlbWFpbBoPCghwcm92aWRlZBIHaW52YWxpZA==",
      "debug": {
        "reason": "INVALID_EMAIL",
        "domain": "validation.example.com",
        "metadata": {
          "field": "email",
          "provided": "invalid"
        }
      }
    }
  ]
}
```

The connect-web client receives:

```typescript
catch (err) {
  if (err instanceof ConnectError) {
    err.code        // Code.InvalidArgument (3)
    err.message     // "email must contain @"
    err.details[0]  // { type: "google.rpc.ErrorInfo", value: Uint8Array [...] }
  }
}
```

## Internal Behavior

The server's error handling pipeline:

1. **Handler throws** `StatusRuntimeException` or `StatusException` with a `Status` and optional details in `grpc-status-details-bin` trailer.
2. **ConnectErrorMapper** catches the exception:
   - Maps `Status.Code` to `ConnectCode` via `ConnectCode.fromGrpc()`
   - Reads the message from `Status.description` (or falls back to the code name)
   - Extracts any `google.rpc.Status` details from the exception's trailers using `StatusProto.fromThrowable()`
3. **ConnectWire** renders the error:
   - For unary: JSON body with code, message, details
   - For streaming: end-of-stream envelope with optional error and metadata
   - For gRPC-Web: trailer frame with gRPC status headers and `grpc-status-details-bin`

If an unexpected exception is thrown (not `StatusException` or `ConnectException`), it is mapped to `UNKNOWN` with the exception's message or class name.

> [!CAUTION]
> Do not throw unchecked exceptions directly from handlers. Wrap them in a `Status` using `Status.UNKNOWN.withCause(exception).asRuntimeException()` or explicitly create a `ConnectException` to ensure the client receives a proper error response instead of a 500.
