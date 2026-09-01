import { ALERT_ID, ALERT_REFERENCE, expect, test } from "./fixtures";

/**
 * Captures the screenshots the README uses.
 *
 * These are generated rather than taken by hand so they cannot drift from the
 * build: every one comes from the same production bundle the e2e suite
 * exercises, and re-running this regenerates them all.
 *
 * All data visible in them is synthetic — served by the stub in `fixtures.ts`,
 * which answers in the contract's own shapes — which is the reason a screenshot
 * of this console can be published at all.
 *
 *   bun run build && bunx playwright test screenshots --project=desktop --update-snapshots
 *
 * Excluded from the normal run by `testIgnore` in playwright.config.ts, because
 * writing files is not a test and does not belong in a check that gates a merge.
 */

/**
 * One entry per screen the README shows.
 *
 * The list is the README's, not the router's: a screen the front page describes
 * and does not show is the gap this covers, and a screen nobody writes about
 * does not need an image that has to be kept current.
 */
const SHOTS = [
  { path: "/", name: "overview", heading: /operations overview/i },
  { path: "/alerts", name: "alert-queue", heading: /alert queue/i },
  {
    path: `/alerts/${ALERT_ID}`,
    name: "investigation",
    heading: new RegExp(`alert ${ALERT_REFERENCE}`, "i"),
  },
  { path: "/reports", name: "reports", heading: /^reports$/i },
  { path: "/health", name: "system-health", heading: /system health/i },
] as const;

test.describe("README screenshots", () => {
  for (const shot of SHOTS) {
    test(`capture ${shot.name}`, async ({ page, api, signIn }) => {
      void api;
      // Both screens are behind the session gate now, so the capture signs in
      // against the same stubbed API the e2e suite uses. No real backend is
      // involved and no real credential exists.
      await signIn(page, shot.path);

      // Wait for the real heading rather than a fixed timeout, so a slow
      // machine produces the same image as a fast one instead of a blank one.
      await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
      await expect(page.getByRole("heading", { level: 1 })).toHaveText(shot.heading);
      await page.waitForLoadState("networkidle");

      // Charts animate on mount. Without this the captured frame is whatever
      // the animation happened to be showing.
      await page.emulateMedia({ reducedMotion: "reduce" });

      // And the pointer goes to a corner first. Signing in ends with a click,
      // which leaves the cursor wherever the button was - and if that lands over
      // a chart, the capture includes a hover tooltip nobody asked for.
      await page.mouse.move(0, 0);
      await page.waitForTimeout(600);

      await page.screenshot({
        path: `../../docs/frontend/screenshots/${shot.name}.png`,
        fullPage: false,
      });
    });
  }
});
