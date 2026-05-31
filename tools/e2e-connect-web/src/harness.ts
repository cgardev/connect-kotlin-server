import { createClient, type Client, ConnectError, Code } from "@connectrpc/connect";
import { createRegistry, type DescService, type Registry } from "@bufbuild/protobuf";
import { expect } from "vitest";
import { connectTransport, grpcWebTransport } from "./transport.js";
import { EchoService, EchoKotlinService, EchoRequestSchema } from "@gen/cgardev/example/v1/echo_pb";

/**
 * The two server implementations under test. The Java `EchoService` and the
 * Kotlin-coroutine `EchoKotlinService` expose the same proto contract and must
 * be indistinguishable on the wire, so every suite runs against both.
 */
export const services = [
  { name: "EchoService", schema: EchoService },
  { name: "EchoKotlinService", schema: EchoKotlinService },
];

/** The two browser-facing protocols the server speaks. */
export const protocols = ["connect", "grpc-web"] as const;

/** The two payload encodings each protocol negotiates. */
export const encodings = ["binary", "json"] as const;

export type ProtocolName = (typeof protocols)[number];
export type EncodingName = (typeof encodings)[number];

/**
 * The full protocol × encoding matrix, pre-labelled (for example
 * `connect/binary`), used by suites that must behave identically on every wire.
 */
export const matrix = protocols.flatMap((protocol) =>
  encodings.map((encoding) => ({ label: `${protocol}/${encoding}`, protocol, encoding })),
);

/** A type registry so JSON transports can (de)serialize `google.protobuf.Any`. */
export const registry: Registry = createRegistry(EchoRequestSchema);

/** The value `GetServerInfo` reports as the server name. */
export const SERVER_NAME = "connect-kotlin-server";

/** Builds a client for one service over a given protocol and encoding. */
export function clientFor<S extends DescService>(
  schema: S,
  protocol: ProtocolName = "connect",
  encoding: EncodingName = "binary",
): Client<S> {
  const transport = protocol === "connect" ? connectTransport : grpcWebTransport;
  return createClient(schema, transport(encoding === "binary", registry));
}

/** Collects an async iterable (a server stream) into an array. */
export async function collect<T>(stream: AsyncIterable<T>): Promise<T[]> {
  const items: T[] = [];
  for await (const item of stream) items.push(item);
  return items;
}

/** The inclusive integer range `[start, end]`, for readable stream assertions. */
export function range(start: number, end: number): number[] {
  return Array.from({ length: end - start + 1 }, (_, index) => start + index);
}

/**
 * Asserts that [call] rejects with a `ConnectError` carrying [code] and, when
 * supplied, a message containing [messageIncludes]. Returns the error for
 * further assertions, and fails the test if the call resolves instead.
 */
export async function expectConnectError(
  call: () => Promise<unknown>,
  code: Code,
  messageIncludes?: string,
): Promise<ConnectError> {
  try {
    await call();
  } catch (error) {
    expect(error).toBeInstanceOf(ConnectError);
    const connectError = error as ConnectError;
    expect(connectError.code).toBe(code);
    if (messageIncludes !== undefined) {
      expect(connectError.rawMessage).toContain(messageIncludes);
    }
    return connectError;
  }
  throw new Error("expected the call to reject with a ConnectError, but it resolved");
}
