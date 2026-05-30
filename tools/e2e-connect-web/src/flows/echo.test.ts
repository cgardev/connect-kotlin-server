import { describe, it, expect } from "vitest";
import { createClient, ConnectError, Code } from "@connectrpc/connect";
import { connectTransport, grpcWebTransport } from "../transport.js";
import { EchoService } from "@gen/cgardev/example/v1/echo_pb";

const protocols = [
  { name: "connect", make: connectTransport },
  { name: "grpc-web", make: grpcWebTransport },
];

const formats = [
  { name: "binary", useBinaryFormat: true },
  { name: "json", useBinaryFormat: false },
];

// Exercise every protocol in both proto-binary and JSON encodings.
const matrix = protocols.flatMap((protocol) =>
  formats.map((format) => ({
    name: `${protocol.name}/${format.name}`,
    transport: protocol.make(format.useBinaryFormat),
  })),
);

for (const variant of matrix) {
  describe(`EchoService over ${variant.name}`, () => {
    const client = createClient(EchoService, variant.transport);

    it("unary Echo", async () => {
      const response = await client.echo({ message: "hi" });
      expect(response.message).toBe("echo: hi");
    });

    it("idempotent GetServerInfo", async () => {
      const response = await client.getServerInfo({});
      expect(response.name).toBe("connect-kotlin-server");
      expect(response.version).toBeTruthy();
    });

    it("server-streaming Count", async () => {
      const numbers: number[] = [];
      for await (const response of client.count({ to: 3 })) {
        numbers.push(response.number);
      }
      expect(numbers).toEqual([1, 2, 3]);
    });

    it("Fail surfaces a Connect error with code and message", async () => {
      try {
        await client.fail({ reason: "kaboom" });
        expect.fail("expected the call to throw");
      } catch (error) {
        expect(error).toBeInstanceOf(ConnectError);
        const connectError = error as ConnectError;
        expect(connectError.code).toBe(Code.InvalidArgument);
        expect(connectError.rawMessage).toContain("kaboom");
      }
    });
  });
}
