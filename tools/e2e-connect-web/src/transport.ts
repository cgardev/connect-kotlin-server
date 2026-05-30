import {
  createConnectTransport,
  createGrpcWebTransport,
} from "@connectrpc/connect-web";
import type { Transport } from "@connectrpc/connect";

export const baseUrl = (): string =>
  process.env.E2E_BASE_URL ?? "http://localhost:8088";

/**
 * The Connect protocol, exactly as a browser connect-web client speaks it.
 * [useBinaryFormat] toggles the proto-binary (`application/proto`) vs JSON
 * (`application/json`) encoding.
 */
export function connectTransport(useBinaryFormat: boolean): Transport {
  return createConnectTransport({ baseUrl: baseUrl(), useBinaryFormat });
}

/**
 * The gRPC-Web protocol, the other browser-facing transport the server serves.
 * [useBinaryFormat] toggles `application/grpc-web+proto` vs `application/grpc-web+json`.
 */
export function grpcWebTransport(useBinaryFormat: boolean): Transport {
  return createGrpcWebTransport({ baseUrl: baseUrl(), useBinaryFormat });
}
