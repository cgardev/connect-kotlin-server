import { describe, it, expect } from "vitest";
import { createClient, ConnectError, Code } from "@connectrpc/connect";
import { create, createRegistry } from "@bufbuild/protobuf";
import { anyPack, anyUnpack } from "@bufbuild/protobuf/wkt";
import { connectTransport, grpcWebTransport } from "../transport.js";
import { EchoService, EchoKotlinService, EchoRequestSchema } from "@gen/cgardev/example/v1/echo_pb";

// The JSON transport needs a registry to (de)serialize the Any payload.
const registry = createRegistry(EchoRequestSchema);

// Both the Java and the Kotlin coroutine service must pass the same suite.
const services = [
  { name: "EchoService", desc: EchoService },
  { name: "EchoKotlinService", desc: EchoKotlinService },
];

for (const service of services) {
  const connectBinary = createClient(service.desc, connectTransport(true));
  const connectJson = createClient(service.desc, connectTransport(false, registry));
  const grpcWeb = createClient(service.desc, grpcWebTransport(true));

  describe(`${service.name}: google.protobuf.Any round-trip`, () => {
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

  describe(`${service.name}: concurrency`, () => {
    it("handles many concurrent unary calls without cross-talk", async () => {
      const count = 64;
      const responses = await Promise.all(
        Array.from({ length: count }, (_, i) => connectBinary.echo({ message: `c${i}` })),
      );
      expect(responses.map((r) => r.message)).toEqual(
        Array.from({ length: count }, (_, i) => `echo: c${i}`),
      );
    });
  });

  describe(`${service.name}: server streaming edge cases`, () => {
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

  describe(`${service.name}: headers and trailers`, () => {
    for (const [name, client] of [
      ["connect", connectBinary],
      ["grpc-web", grpcWeb],
    ] as const) {
      it(`propagates request metadata to a response header and trailer over ${name}`, async () => {
        let header: string | null = null;
        let trailer: string | null = null;

        const response = await client.echo(
          { message: "m" },
          {
            headers: { "x-echo": "hi" },
            onHeader: (headers) => {
              header = headers.get("x-echo-header");
            },
            onTrailer: (trailers) => {
              trailer = trailers.get("x-echo-trailer");
            },
          },
        );

        expect(response.message).toBe("echo: m");
        expect(header).toBe("hi");
        expect(trailer).toBe("hi");
      });
    }
  });

  describe(`${service.name}: errors`, () => {
    for (const [reason, grpcCode, expected] of [
      ["kaboom", 0, Code.InvalidArgument],
      ["nope", 5, Code.NotFound],
      ["denied", 7, Code.PermissionDenied],
      ["who", 16, Code.Unauthenticated],
    ] as const) {
      it(`surfaces the Connect code ${expected}`, async () => {
        try {
          await connectBinary.fail({ reason, grpcCode });
          expect.fail("expected the call to throw");
        } catch (error) {
          expect(error).toBeInstanceOf(ConnectError);
          expect((error as ConnectError).code).toBe(expected);
        }
      });
    }

    it("carries the failure message", async () => {
      try {
        await connectBinary.fail({ reason: "kaboom" });
        expect.fail("expected the call to throw");
      } catch (error) {
        expect((error as ConnectError).rawMessage).toContain("kaboom");
      }
    });
  });
}
