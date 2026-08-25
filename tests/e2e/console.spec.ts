import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";

/** Every route the Phase 0 foundation is expected to render. */
const ROUTES = [
  { path: "/", name: "Operations overview" },
  { path: "/transactions/live", name: "Live transactions" },
  { path: "/alerts", name: "Alert queue" },
  { path: "/reports", name: "Reports" },
  { path: "/model", name: "Model & policy" },
  { path: "/health", name: "System health" },
  { path: "/about", name: "About" },
  { path: "/login", name: "Sign in" },
] as const;

/** Mock queries resolve on a fixed delay; wait for real content, not a skeleton. */
async function gotoSettled(page: Page, path: string): Promise<void> {
  await page.goto(path);
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
  await page.waitForLoadState("networkidle");
}

test.describe("routes render", () => {
  for (const route of ROUTES) {
    test(`${route.name} (${route.path}) renders with a heading and no console errors`, async ({
      page,
    }) => {
      const errors: string[] = [];
      page.on("console", (msg) => {
        if (msg.type() === "error") errors.push(msg.text());
      });
      page.on("pageerror", (err) => errors.push(err.message));

      await gotoSettled(page, route.path);

      await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
      expect(errors, `console errors on ${route.path}`).toEqual([]);
    });
  }

  test("an unknown route renders the 404 screen", async ({ page }) => {
    await page.goto("/this-route-does-not-exist");
    await expect(page.getByText(/page not found/i)).toBeVisible();
  });

  test("every route is directly reachable without passing through sign-in", async ({ page }) => {
    // The foundation must not gate routes: deep links land where they point.
    await page.goto("/alerts");
    await expect(page).toHaveURL(/\/alerts$/);
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
  });
});

test.describe("synthetic-data disclosure", () => {
  test("the console states it is synthetic and unaffiliated", async ({ page }) => {
    await gotoSettled(page, "/");
    await expect(page.getByText(/fictional synthetic data/i).first()).toBeVisible();
    await expect(page.getByText(/not affiliated with any bank/i).first()).toBeVisible();
  });

  test("the sign-in screen denies being real authentication", async ({ page }) => {
    await gotoSettled(page, "/login");
    await expect(page.getByText(/no authentication, no credential check/i)).toBeVisible();
    // A password field would mean credential handling, which this phase excludes.
    await expect(page.locator('input[type="password"]')).toHaveCount(0);
  });
});

test.describe("mock data layer", () => {
  test("issues no request to a real /api/v1 backend", async ({ page }) => {
    const apiCalls: string[] = [];
    page.on("request", (req) => {
      const url = new URL(req.url());
      if (url.pathname.startsWith("/api/")) apiCalls.push(req.url());
    });

    await gotoSettled(page, "/alerts");
    await gotoSettled(page, "/");

    expect(apiCalls, "mock mode must not reach a backend").toEqual([]);
  });

  test("renders alert rows from the deterministic fixtures", async ({ page }) => {
    await gotoSettled(page, "/alerts");
    const rows = page.getByRole("row");
    expect(await rows.count()).toBeGreaterThan(1);
    await expect(page.getByText(/ALR-/).first()).toBeVisible();
  });

  test("opens an alert detail from the queue", async ({ page }) => {
    await gotoSettled(page, "/alerts");
    await page.getByRole("link", { name: /ALR-/ }).first().click();
    await expect(page).toHaveURL(/\/alerts\/ALR-/);
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
  });
});

test.describe("keyboard operation", () => {
  test("exposes a skip link as the first tab stop", async ({ page }) => {
    await gotoSettled(page, "/");
    await page.keyboard.press("Tab");
    const focused = page.locator(":focus");
    await expect(focused).toHaveText(/skip to main content/i);
  });

  test("the skip link moves focus to the main landmark", async ({ page }) => {
    await gotoSettled(page, "/");
    await page.keyboard.press("Tab");
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(/#main-content$/);
  });

  test("primary navigation is reachable and operable by keyboard", async ({ page }) => {
    await gotoSettled(page, "/");
    const alertsLink = page.getByRole("link", { name: "Alert queue" });
    await alertsLink.focus();
    await expect(alertsLink).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(/\/alerts$/);
  });

  test("focused controls have a visible focus indicator", async ({ page }) => {
    await gotoSettled(page, "/");
    const link = page.getByRole("link", { name: "Alert queue" });
    await link.focus();
    const outline = await link.evaluate((el) => {
      const s = getComputedStyle(el);
      return { width: s.outlineWidth, style: s.outlineStyle, shadow: s.boxShadow };
    });
    const hasOutline = outline.style !== "none" && parseFloat(outline.width) > 0;
    const hasRing = outline.shadow !== "none" && outline.shadow !== "";
    expect(hasOutline || hasRing, "focused element must be visibly indicated").toBe(true);
  });
});

test.describe("accessibility (axe, real browser)", () => {
  for (const route of ROUTES) {
    test(`${route.name} has no WCAG 2.1 A/AA violations`, async ({ page }) => {
      await gotoSettled(page, route.path);

      const results = await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
        .analyze();

      const summary = results.violations.map(
        (v) => `${v.id} (${v.impact}) x${v.nodes.length}: ${v.help}`,
      );
      expect(summary, `axe violations on ${route.path}`).toEqual([]);
    });
  }
});

test.describe("responsive layout", () => {
  test("does not scroll horizontally at tablet width", async ({ page }) => {
    await page.setViewportSize({ width: 768, height: 1024 });
    await gotoSettled(page, "/alerts");
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    );
    expect(overflow, "page body must not overflow horizontally").toBeLessThanOrEqual(1);
  });

  test("keeps the overview usable at desktop width", async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await gotoSettled(page, "/");
    await expect(page.getByRole("navigation", { name: /primary/i })).toBeVisible();
    await expect(page.getByRole("main")).toBeVisible();
  });
});
