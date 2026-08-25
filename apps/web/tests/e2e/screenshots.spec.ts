import { expect, test } from "@playwright/test";

/**
 * Captures the screenshots the README uses.
 *
 * These are generated rather than taken by hand so they cannot drift from the
 * build: every one comes from the same production bundle the e2e suite
 * exercises, and re-running this regenerates them all.
 *
 * All data visible in them is synthetic - the fixtures in src/mocks/ - which is
 * the reason a screenshot of this console can be published at all.
 *
 *   bun run build && bunx playwright test screenshots --project=desktop --update-snapshots
 *
 * Excluded from the normal run by `testIgnore` in playwright.config.ts, because
 * writing files is not a test and does not belong in a check that gates a merge.
 */

const SHOTS = [
  { path: "/", name: "overview", heading: /operations overview/i },
  { path: "/alerts", name: "alert-queue", heading: /alert queue/i },
] as const;

test.describe("README screenshots", () => {
  for (const shot of SHOTS) {
    test(`capture ${shot.name}`, async ({ page }) => {
      await page.goto(shot.path);

      // Wait for the real heading rather than a fixed timeout, so a slow
      // machine produces the same image as a fast one instead of a blank one.
      await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
      await expect(page.getByRole("heading", { level: 1 })).toHaveText(shot.heading);
      await page.waitForLoadState("networkidle");

      // Charts animate on mount. Without this the captured frame is whatever
      // the animation happened to be showing.
      await page.emulateMedia({ reducedMotion: "reduce" });
      await page.waitForTimeout(600);

      await page.screenshot({
        path: `../../docs/frontend/screenshots/${shot.name}.png`,
        fullPage: false,
      });
    });
  }
});
