import { defineConfig, devices } from "@playwright/test";

const PORT = 4173;
const BASE_URL = `http://127.0.0.1:${PORT}`;

/**
 * Browser verification for the SentinelFlow console.
 *
 * These run against the real production build rather than the dev server, so
 * what is checked is what would actually ship. Contrast, focus visibility, and
 * layout cannot be verified under jsdom, so the accessibility assertions that
 * matter live here.
 */
export default defineConfig({
  testDir: "./tests/e2e",
  // Screenshot capture writes files into docs/. That is a generation task, not
  // a test, so it stays out of the suite that gates a merge and is run
  // explicitly:  bunx playwright test screenshots --project=desktop
  testIgnore: process.env["CAPTURE_SCREENSHOTS"] ? [] : ["**/screenshots.spec.ts"],
  fullyParallel: true,
  forbidOnly: !!process.env["CI"],
  retries: process.env["CI"] ? 2 : 0,
  workers: process.env["CI"] ? 1 : undefined,
  reporter: process.env["CI"] ? [["github"], ["html", { open: "never" }]] : [["list"]],
  timeout: 30_000,
  expect: { timeout: 10_000 },
  use: {
    baseURL: BASE_URL,
    trace: "on-first-retry",
    screenshot: "only-on-failure",
  },
  projects: [
    {
      name: "desktop",
      use: { ...devices["Desktop Chrome"], viewport: { width: 1440, height: 900 } },
    },
    {
      // Tablet viewport on Chromium. This project exists to verify responsive
      // layout, not cross-engine rendering, so it deliberately does not pull in
      // a second browser download for CI.
      name: "tablet",
      use: {
        ...devices["Desktop Chrome"],
        viewport: { width: 768, height: 1024 },
        hasTouch: true,
        isMobile: false,
      },
    },
  ],
  webServer: {
    command: `bunx vite preview --port ${PORT} --strictPort`,
    url: BASE_URL,
    reuseExistingServer: !process.env["CI"],
    timeout: 120_000,
  },
});
