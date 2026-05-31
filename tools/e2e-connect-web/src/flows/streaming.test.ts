import { describe, it, expect } from "vitest";
import { services, protocols, clientFor, collect, range } from "../harness.js";

/**
 * Server-streaming specifics. Connect and gRPC-Web frame streams differently, so
 * each wire protocol is exercised independently (binary encoding is enough here,
 * since framing is what differs, not the payload format).
 */
for (const service of services) {
  for (const protocol of protocols) {
    describe(`${service.name} · ${protocol} streaming`, () => {
      const client = clientFor(service.schema, protocol);

      it("delivers a long stream in order", async () => {
        const responses = await collect(client.count({ to: 100 }));
        expect(responses.map((response) => response.number)).toEqual(range(1, 100));
      });

      it("delivers nothing for an empty stream", async () => {
        const responses = await collect(client.count({ to: 0 }));
        expect(responses).toEqual([]);
      });
    });
  }
}
