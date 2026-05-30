import { defineConfig } from "vitest/config";
import path from "path";

export default defineConfig({
  resolve: {
    alias: {
      "@gen": path.resolve(__dirname, "src/gen"),
    },
  },
  test: {
    testTimeout: 60_000,
    hookTimeout: 120_000,
    globalSetup: "./src/global-setup.ts",
    sequence: {
      concurrent: false,
    },
    fileParallelism: false,
  },
});
