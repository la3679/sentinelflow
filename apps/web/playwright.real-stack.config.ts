import { defineConfig, devices } from "@playwright/test";

/**
 * Console verification against the running Docker Compose stack.
 *
 * The suite in `tests/e2e` stubs the API at the network boundary, and the suite
 * in `apps/api` runs against Testcontainers. Neither is the stack a demo runs
 * on, and three defects that a fully green build was blind to were only ever
 * visible on the compose stack: nothing created the Kafka topics, the scoring
 * client negotiated HTTP/2 against an HTTP/1.1-only service, and two PowerShell
 * targets had never worked.
 *
 * So this config points at no server of its own. It drives the console image
 * compose publishes, which calls the API image compose publishes, which reads
 * the PostgreSQL compose publishes. Nothing here is stubbed and nothing here is
 * seeded by the test: it works with the operators and alerts the stack already
 * holds, and skips rather than lies when the stack is not up.
 *
 *   scripts/dev/sf.ps1 up   (or make up)
 *   make verify-real-stack  (or scripts/dev/sf.ps1 verify-real-stack)
 */
const WEB_PORT = process.env["WEB_PORT"] ?? "5173";
const BASE_URL = process.env["SENTINELFLOW_WEB_URL"] ?? `http://localhost:${WEB_PORT}`;

export default defineConfig({
  testDir: "./tests/real-stack",
  // A stack behind a cold JIT answers the first queue read slowly, and this
  // suite is never the thing that should be flaky about it.
  timeout: 90_000,
  expect: { timeout: 20_000 },
  // Two workers assigning alerts in the same database would race each other
  // into the 409 this suite provokes deliberately.
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env["CI"],
  retries: 0,
  reporter: [["list"]],
  use: {
    baseURL: BASE_URL,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [
    {
      name: "desktop",
      use: { ...devices["Desktop Chrome"], viewport: { width: 1440, height: 900 } },
    },
  ],
});
