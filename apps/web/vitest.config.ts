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
      // A ratchet, set below the measurement it was taken from and never
      // lowered to go green - the same rule apps/api and apps/scoring follow.
      //
      // WHAT THIS NUMBER IS AND IS NOT. Measured at 26.79% of statements on
      // 2026-08-29 (`bun run test:coverage`, 41 tests). It is low because most
      // of this console's behaviour is asserted by Playwright against a real
      // browser rather than by Vitest against jsdom: focus visibility, keyboard
      // operation, contrast and axe cannot be checked in a unit test, and the
      // route components that make up most of these lines are exercised there.
      //
      // So this gate is not a claim that the console is 26% tested. It stops
      // the unit layer silently shrinking - a deleted transport or store test
      // takes the number below the floor and fails the run, which is the only
      // job a threshold at this level can honestly do.
      thresholds: {
        statements: 25,
        branches: 17,
        functions: 18,
        lines: 25,
      },
    },
  },
});
