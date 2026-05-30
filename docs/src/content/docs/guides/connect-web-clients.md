---
title: "Calling from the browser (connect-web)"
description: "Build a TypeScript/browser client with @connectrpc/connect-web to call connect-kotlin-server services over Connect or gRPC-Web."
---

The connect-kotlin-server serves your services over the [Connect protocol](https://connectrpc.com/docs/protocol) and gRPC-Web, making them callable from browser clients. This guide shows how to build a TypeScript client using [`@connectrpc/connect-web`](https://www.npmjs.com/package/@connectrpc/connect-web).

## Overview

A browser client using connect-web:

1. **Generates TypeScript clients** from your `.proto` files using `buf` and `protoc-gen-es`
2. **Builds a transport** with either `createConnectTransport` (Connect protocol) or `createGrpcWebTransport` (gRPC-Web)
3. **Creates a client** bound to the transport
4. **Calls unary and server-streaming methods** just like a backend client

The transport can use proto-binary (`application/proto` for Connect, `application/grpc-web+proto` for gRPC-Web) or JSON encoding—toggled by a single option.

## Generate the client

Create a `buf.gen.yaml` in your client directory that points to your proto files:

```yaml title="buf.gen.yaml"
version: v2
inputs:
  - directory: ../path/to/proto
plugins:
  - local: protoc-gen-es
    out: src/gen
    opt:
      - target=ts
```

Then generate:

```bash
buf generate
```

This outputs TypeScript client stubs to `src/gen/`. For the example service:

```typescript
import { EchoService } from "@gen/cgardev/example/v1/echo_pb";
```

## Build a transport

Create transport instances for either protocol. Each transport takes a base URL and an encoding option.

```typescript title="transport.ts"
import {
  createConnectTransport,
  createGrpcWebTransport,
} from "@connectrpc/connect-web";
import type { Transport } from "@connectrpc/connect";

const baseUrl = "http://localhost:8080";

// Connect protocol with proto-binary encoding
export function connectTransport(): Transport {
  return createConnectTransport({
    baseUrl,
    useBinaryFormat: true,
  });
}

// Connect protocol with JSON encoding
export function connectTransportJson(): Transport {
  return createConnectTransport({
    baseUrl,
    useBinaryFormat: false,
  });
}

// gRPC-Web protocol with proto-binary encoding
export function grpcWebTransport(): Transport {
  return createGrpcWebTransport({
    baseUrl,
    useBinaryFormat: true,
  });
}

// gRPC-Web protocol with JSON encoding
export function grpcWebTransportJson(): Transport {
  return createGrpcWebTransport({
    baseUrl,
    useBinaryFormat: false,
  });
}
```

Both protocols are fully supported by the server over all encoding combinations.

> [!TIP]
> Use proto-binary for efficiency in production; JSON for human readability during development.

## Create a client and call methods

Create a client by passing the service and transport to `createClient`:

```typescript
import { createClient } from "@connectrpc/connect";
import { EchoService } from "@gen/cgardev/example/v1/echo_pb";
import { connectTransport } from "./transport";

const transport = connectTransport();
const client = createClient(EchoService, transport);
```

### Unary calls

Call unary methods directly and await the response:

```typescript
// Unary call (Echo)
const response = await client.echo({ message: "hello" });
console.log(response.message); // "echo: hello"

// Idempotent call (GET-eligible on the server)
const info = await client.getServerInfo({});
console.log(info.name); // "connect-kotlin-server"
console.log(info.version);
```

### Server-streaming calls

Server-streaming methods return an async iterable. Iterate to consume the stream:

```typescript
// Server-streaming call (Count)
const numbers: number[] = [];
for await (const response of client.count({ to: 5 })) {
  numbers.push(response.number);
}
console.log(numbers); // [1, 2, 3, 4, 5]
```

### Error handling

Errors thrown by the server are converted to `ConnectError` with full code and message:

```typescript
import { ConnectError, Code } from "@connectrpc/connect";

try {
  await client.fail({ reason: "kaboom" });
} catch (error) {
  if (error instanceof ConnectError) {
    console.log(error.code); // Code.InvalidArgument
    console.log(error.rawMessage); // "kaboom"
  }
}
```

## Complete example

```typescript title="client.ts"
import { createClient, ConnectError, Code } from "@connectrpc/connect";
import { EchoService } from "@gen/cgardev/example/v1/echo_pb";
import { connectTransport } from "./transport";

async function main() {
  const transport = connectTransport();
  const client = createClient(EchoService, transport);

  // Unary
  const echoResponse = await client.echo({ message: "hi" });
  console.log("Echo:", echoResponse.message);

  // Server-streaming
  console.log("Counting:");
  for await (const response of client.count({ to: 3 })) {
    console.log(" ", response.number);
  }

  // Error handling
  try {
    await client.fail({ reason: "test error" });
  } catch (error) {
    if (error instanceof ConnectError) {
      console.log("Error:", error.code, error.rawMessage);
    }
  }
}

main().catch(console.error);
```

## Testing: the e2e-connect-web suite

The repository includes `tools/e2e-connect-web`, a [Vitest](https://vitest.dev/) end-to-end suite that:

- **Starts the server** (`bootRun`) automatically
- **Generates the client** from proto using `buf`
- **Exercises 16 test cases**: 4 RPC methods × 2 protocols (Connect, gRPC-Web) × 2 encodings (proto-binary, JSON)

Run the tests:

```bash
cd tools/e2e-connect-web
pnpm install
pnpm test
```

This validates that your setup is correct and the server handles all combinations properly.

## CORS

The server ships with permissive CORS enabled by default for browser clients. If you need to restrict it, see [Configuration](/connect-kotlin-server/guides/configuration).

## Further reading

- [Connect RPC protocol documentation](https://connectrpc.com/)
- [@connectrpc/connect-web API reference](https://github.com/connectrpc/connect-es)
- [Protocol support](/connect-kotlin-server/guides/protocols-and-codecs) in the main README
