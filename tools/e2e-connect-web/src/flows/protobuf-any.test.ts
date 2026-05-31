import { describe, it, expect } from "vitest";
import { create } from "@bufbuild/protobuf";
import { anyPack, anyUnpack } from "@bufbuild/protobuf/wkt";
import { EchoRequestSchema, type EchoRequest } from "@gen/cgardev/example/v1/echo_pb";
import { services, encodings, clientFor, registry } from "../harness.js";

/**
 * `google.protobuf.Any` survives the round-trip in both binary and JSON. JSON
 * relies on the shared type registry to (de)serialize the packed message; binary
 * does not. The server unpacks nothing — it echoes the Any back untouched — so a
 * faithful round-trip proves the encoding carries Any end to end.
 */
for (const service of services) {
  describe(`${service.name} · Any round-trip`, () => {
    for (const encoding of encodings) {
      it(`packs and unpacks an Any over connect/${encoding}`, async () => {
        const client = clientFor(service.schema, "connect", encoding);
        const payload = anyPack(EchoRequestSchema, create(EchoRequestSchema, { message: "packed" }));

        const response = await client.roundTrip({ payload, label: encoding });

        expect(response.label).toBe(`roundtrip:${encoding}`);
        const unpacked = anyUnpack(response.payload!, registry) as EchoRequest | undefined;
        expect(unpacked?.$typeName).toBe("cgardev.example.v1.EchoRequest");
        expect(unpacked?.message).toBe("packed");
      });
    }
  });
}
