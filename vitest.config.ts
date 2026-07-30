import { resolve } from "node:path";
import { defineConfig } from "vitest/config";

export default defineConfig({
  resolve: {
    alias: { "@": resolve(__dirname, "./src") },
  },
  test: {
    environment: "node",
    include: ["tests/**/*.test.ts"],
    env: {
      // Keep the integration tests hermetic and fast: no disk cache between
      // runs, and no per-host throttle sleeping between stubbed requests.
      CACHE_TTL_SECONDS: "0",
      SCRAPE_MIN_INTERVAL_MS: "0",
    },
  },
});
