import {
  createConnectTransport,
  createGrpcWebTransport,
} from "@connectrpc/connect-web";
import type { Transport } from "@connectrpc/connect";
import type { Registry } from "@bufbuild/protobuf";

export const baseUrl = (): string =>
  process.env.E2E_BASE_URL ?? "http://localhost:8088";

// A type registry is required to (de)serialize google.protobuf.Any in JSON.
const jsonOptions = (registry?: Registry) => (registry ? { jsonOptions: { registry } } : {});

/**
 * The Connect protocol, exactly as a browser connect-web client speaks it.
 * [useBinaryFormat] toggles the proto-binary (`application/proto`) vs JSON
 * (`application/json`) encoding; [registry] enables Any encoding under JSON.
 */
export function connectTransport(useBinaryFormat: boolean, registry?: Registry): Transport {
  return createConnectTransport({ baseUrl: baseUrl(), useBinaryFormat, ...jsonOptions(registry) });
}

/**
 * The gRPC-Web protocol, the other browser-facing transport the server serves.
 * [useBinaryFormat] toggles `application/grpc-web+proto` vs `application/grpc-web+json`.
 */
export function grpcWebTransport(useBinaryFormat: boolean, registry?: Registry): Transport {
  return createGrpcWebTransport({ baseUrl: baseUrl(), useBinaryFormat, ...jsonOptions(registry) });
}
