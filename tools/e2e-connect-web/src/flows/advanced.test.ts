import { describe, it, expect } from "vitest";
import { createClient } from "@connectrpc/connect";
import { create, createRegistry } from "@bufbuild/protobuf";
import { anyPack, anyUnpack } from "@bufbuild/protobuf/wkt";
import { connectTransport, grpcWebTransport } from "../transport.js";
import { EchoService, EchoRequestSchema } from "@gen/cgardev/example/v1/echo_pb";

// The JSON transport needs a registry to (de)serialize the Any payload.
const registry = createRegistry(EchoRequestSchema);
const connectBinary = createClient(EchoService, connectTransport(true));
const connectJson = createClient(EchoService, connectTransport(false, registry));
const grpcWeb = createClient(EchoService, grpcWebTransport(true));

describe("google.protobuf.Any round-trip", () => {
  for (const [encoding, client] of [
    ["binary", connectBinary],
    ["json", connectJson],
  ] as const) {
    it(`packs and unpacks an Any over connect/${encoding}`, async () => {
      const payload = anyPack(EchoRequestSchema, create(EchoRequestSchema, { message: "packed" }));

      const response = await client.roundTrip({ payload, label: encoding });

      expect(response.label).toBe(`roundtrip:${encoding}`);
      const unpacked = anyUnpack(response.payload!, registry);
      expect(unpacked?.$typeName).toBe("cgardev.example.v1.EchoRequest");
      expect((unpacked as { message: string }).message).toBe("packed");
    });
  }
});

describe("concurrency", () => {
  it("handles many concurrent unary calls without cross-talk", async () => {
    const count = 64;
    const responses = await Promise.all(
      Array.from({ length: count }, (_, i) => connectBinary.echo({ message: `c${i}` })),
    );
    // Promise.all preserves order, so each response must echo its own request.
    expect(responses.map((r) => r.message)).toEqual(
      Array.from({ length: count }, (_, i) => `echo: c${i}`),
    );
  });
});

describe("server streaming edge cases", () => {
  for (const [name, client] of [
    ["connect", connectBinary],
    ["grpc-web", grpcWeb],
  ] as const) {
    it(`streams a large result in order over ${name}`, async () => {
      const numbers: number[] = [];
      for await (const response of client.count({ to: 100 })) numbers.push(response.number);
      expect(numbers).toEqual(Array.from({ length: 100 }, (_, i) => i + 1));
    });

    it(`yields nothing for an empty stream over ${name}`, async () => {
      const numbers: number[] = [];
      for await (const response of client.count({ to: 0 })) numbers.push(response.number);
      expect(numbers).toEqual([]);
    });
  }
});
