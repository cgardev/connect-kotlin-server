import { describe, it, expect } from "vitest";
import { services, protocols, clientFor } from "../harness.js";

/**
 * Request metadata round-trips. The demo service copies the request header
 * `x-echo` into both a response header (`x-echo-header`) and a trailer
 * (`x-echo-trailer`), so a single call proves leading and trailing metadata
 * both survive on each wire protocol.
 */
for (const service of services) {
  for (const protocol of protocols) {
    describe(`${service.name} · ${protocol} metadata`, () => {
      const client = clientFor(service.schema, protocol);

      it("echoes a request header into the response header and trailer", async () => {
        let header: string | null = null;
        let trailer: string | null = null;

        const { message } = await client.echo(
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

        expect(message).toBe("echo: m");
        expect(header).toBe("hi");
        expect(trailer).toBe("hi");
      });
    });
  }
}
