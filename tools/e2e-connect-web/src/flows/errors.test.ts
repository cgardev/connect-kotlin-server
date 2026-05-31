import { describe, it } from "vitest";
import { Code } from "@connectrpc/connect";
import { services, clientFor, expectConnectError } from "../harness.js";

/**
 * The gRPC status → Connect code mapping. The demo `Fail` method throws with a
 * requested gRPC code (0 falls back to INVALID_ARGUMENT), and the browser client
 * must observe the matching Connect code.
 */
const ERROR_CASES = [
  { name: "InvalidArgument", grpcCode: 0, reason: "kaboom", expected: Code.InvalidArgument },
  { name: "NotFound", grpcCode: 5, reason: "nope", expected: Code.NotFound },
  { name: "PermissionDenied", grpcCode: 7, reason: "denied", expected: Code.PermissionDenied },
  { name: "Unauthenticated", grpcCode: 16, reason: "who", expected: Code.Unauthenticated },
];

for (const service of services) {
  describe(`${service.name} · error mapping`, () => {
    const client = clientFor(service.schema);

    for (const { name, grpcCode, reason, expected } of ERROR_CASES) {
      it(`maps gRPC code ${grpcCode} to ${name}`, async () => {
        await expectConnectError(() => client.fail({ reason, grpcCode }), expected);
      });
    }

    it("carries the failure message", async () => {
      await expectConnectError(() => client.fail({ reason: "kaboom" }), Code.InvalidArgument, "kaboom");
    });
  });
}
