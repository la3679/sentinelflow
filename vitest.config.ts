import { fileURLToPath } from "node:url";

import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

// Deliberately separate from vite.config.ts: the application config wraps the
// full TanStack Start + Nitro plugin chain, which is irrelevant to unit tests
// and slows them down. Tests only need React, path aliases, and jsdom.
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./tests/setup/vitest.setup.ts"],
    include: ["tests/unit/**/*.test.{ts,tsx}"],
    coverage: {
      provider: "v8",
      reporter: ["text-summary", "lcov"],
      reportsDirectory: "coverage",
      include: ["src/**/*.{ts,tsx}"],
      exclude: [
        "src/components/ui/**",
        "src/routeTree.gen.ts",
        "src/lib/lovable-error-reporting.ts",
        "src/lib/error-capture.ts",
        "src/lib/error-page.ts",
        "src/server.ts",
        "src/start.ts",
      ],
    },
  },
});
