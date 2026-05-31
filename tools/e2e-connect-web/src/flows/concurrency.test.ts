import { describe, it, expect } from "vitest";
import { services, clientFor, range } from "../harness.js";

/**
 * Many in-flight unary calls on a single client must not cross-talk: every
 * response has to match its own request, proving the dispatcher keeps concurrent
 * calls independent.
 */
const CONCURRENT_CALLS = 64;

for (const service of services) {
  describe(`${service.name} · concurrency`, () => {
    const client = clientFor(service.schema);

    it(`keeps ${CONCURRENT_CALLS} concurrent unary calls independent`, async () => {
      const indices = range(0, CONCURRENT_CALLS - 1);

      const responses = await Promise.all(indices.map((index) => client.echo({ message: `c${index}` })));

      expect(responses.map((response) => response.message)).toEqual(indices.map((index) => `echo: c${index}`));
    });
  });
}
