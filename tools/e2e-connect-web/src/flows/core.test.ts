import { describe, it, expect } from "vitest";
import { Code } from "@connectrpc/connect";
import { services, matrix, clientFor, collect, expectConnectError, SERVER_NAME } from "../harness.js";

/**
 * The core RPC surface, swept across every protocol × encoding for both server
 * implementations. These are the table-stakes guarantees: the same call yields
 * the same result no matter how it is framed on the wire.
 */
for (const service of services) {
  for (const { label, protocol, encoding } of matrix) {
    describe(`${service.name} · ${label}`, () => {
      const client = clientFor(service.schema, protocol, encoding);

      it("echoes a unary message", async () => {
        const { message } = await client.echo({ message: "hi" });
        expect(message).toBe("echo: hi");
      });

      it("answers the idempotent GetServerInfo", async () => {
        const info = await client.getServerInfo({});
        expect(info.name).toBe(SERVER_NAME);
        expect(info.version).toBeTruthy();
      });

      it("streams Count in order", async () => {
        const responses = await collect(client.count({ to: 3 }));
        expect(responses.map((response) => response.number)).toEqual([1, 2, 3]);
      });

      it("maps a failed call to a Connect error", async () => {
        await expectConnectError(() => client.fail({ reason: "kaboom" }), Code.InvalidArgument, "kaboom");
      });
    });
  }
}
