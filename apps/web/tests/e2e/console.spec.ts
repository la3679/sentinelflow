import AxeBuilder from "@axe-core/playwright";
import type { Page } from "@playwright/test";

import { expect, test } from "./fixtures";

/** Every route the console is expected to render, and whether it needs a session. */
const ROUTES = [
  { path: "/", name: "Operations overview", session: true },
  { path: "/transactions/live", name: "Live transactions", session: true },
  { path: "/alerts", name: "Alert queue", session: true },
  { path: "/reports", name: "Reports", session: true },
  { path: "/model", name: "Model & policy", session: true },
  { path: "/health", name: "System health", session: true },
  { path: "/about", name: "About", session: false },
  { path: "/login", name: "Sign in", session: false },
] as const;

/** Queries resolve on a delay; wait for real content, not a skeleton. */
async function gotoSettled(page: Page, path: string): Promise<void> {
  await page.goto(path);
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
  await page.waitForLoadState("networkidle");
}

test.describe("routes render", () => {
  for (const route of ROUTES) {
    test(`${route.name} (${route.path}) renders with a heading and no console errors`, async ({
      page,
      api,
      signIn,
    }) => {
      void api;
      const errors: string[] = [];
      page.on("console", (msg) => {
        if (msg.type() === "error") errors.push(msg.text());
      });
      page.on("pageerror", (err) => errors.push(err.message));

      if (route.session) await signIn(page, route.path);
      else await gotoSettled(page, route.path);

      await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
      expect(errors, `console errors on ${route.path}`).toEqual([]);
    });
  }

  test("an unknown route renders the 404 screen", async ({ page }) => {
    await page.goto("/this-route-does-not-exist");
    await expect(page.getByText(/page not found/i)).toBeVisible();
  });
});

test.describe("the session gate", () => {
  test("a deep link without a session goes to sign-in and comes back afterwards", async ({
    page,
    api,
    signIn,
  }) => {
    void api;
    await page.goto("/alerts");
    await expect(page).toHaveURL(/\/login\?next=%2Falerts$/);

    await signIn(page, "/alerts");
    await expect(page).toHaveURL(/\/alerts$/);
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
  });

  test("the disclosure of what this project is stays readable without signing in", async ({
    page,
    api,
  }) => {
    void api;
    await gotoSettled(page, "/about");

    await expect(page).toHaveURL(/\/about$/);
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
  });

  test("signing out ends the session and the deep link is gated again", async ({
    page,
    api,
    signIn,
  }) => {
    void api;
    await signIn(page, "/alerts");
    await page.getByRole("button", { name: /sign out/i }).click();
    await expect(page).toHaveURL(/\/login/);

    await page.goto("/alerts");
    await expect(page).toHaveURL(/\/login\?next=%2Falerts$/);
  });

  test("a reload signs the operator out, because the token is held in memory", async ({
    page,
    api,
    signIn,
  }) => {
    void api;
    await signIn(page, "/alerts");

    await page.reload();

    // ADR-0012 section 3 and the frontend rules: a token that survived a reload
    // would be a credential written to browser storage.
    await expect(page).toHaveURL(/\/login/);
  });
});

test.describe("synthetic-data disclosure", () => {
  test("the console states it is synthetic and unaffiliated", async ({ page, api, signIn }) => {
    void api;
    await signIn(page, "/");
    await expect(page.getByText(/fictional synthetic data/i).first()).toBeVisible();
    await expect(page.getByText(/not affiliated with any bank/i).first()).toBeVisible();
  });

  test("the sign-in screen says what happens to the token it obtains", async ({ page }) => {
    await gotoSettled(page, "/login");
    await expect(page.getByText(/held in memory/i)).toBeVisible();
    await expect(page.getByText(/never written to browser storage/i)).toBeVisible();
    await expect(page.locator('input[type="password"]')).toHaveCount(1);
  });

  test("there is no way to choose your own role", async ({ page, api, signIn }) => {
    void api;
    await signIn(page, "/alerts");

    // Choosing your own role is the interface equivalent of naming your own
    // actor. The role comes from the login response and is displayed, not set.
    await expect(page.getByLabel(/simulated role/i)).toHaveCount(0);
    await expect(page.getByText(/analyst\.one/)).toBeVisible();
  });
});

test.describe("the transport", () => {
  test("signing in is the one request the console makes, and it carries no token", async ({
    page,
    api,
    signIn,
  }) => {
    await signIn(page, "/alerts");
    // Navigated in the application rather than reloaded: a reload drops the
    // in-memory token, which is a different test.
    await page.getByRole("link", { name: "Operations overview" }).click();
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();

    // The screens behind these routes still read fixtures: the four endpoints
    // they want have no server counterpart yet. See
    // docs/frontend/API_MIGRATION_AUDIT.md. When they gain one this expectation
    // changes, rather than the console quietly starting to make requests.
    expect(api.requests.map((r) => `${r.method} ${r.path}`)).toEqual(["POST /api/v1/auth/login"]);
    expect(api.requests[0]?.authorization, "a login carries no bearer token").toBeNull();
  });

  test("a refused sign-in says so without saying which half was wrong", async ({ page, api }) => {
    api.refuseLogin();
    await page.goto("/login");
    await page.getByLabel("Username").fill("analyst.one");
    await page.getByLabel("Password").fill("not-the-password");
    await page.getByRole("button", { name: "Sign in" }).click();

    await expect(page.getByRole("alert")).toHaveText(
      /the username and password were not accepted/i,
    );
    await expect(page).toHaveURL(/\/login/);
  });

  test("renders alert rows from the deterministic fixtures", async ({ page, api, signIn }) => {
    void api;
    await signIn(page, "/alerts");
    // The queue resolves on a delay; wait for a row rather than counting a
    // skeleton.
    await expect(page.getByText(/ALR-/).first()).toBeVisible();
    expect(await page.getByRole("row").count()).toBeGreaterThan(1);
  });

  test("opens an alert detail from the queue", async ({ page, api, signIn }) => {
    void api;
    await signIn(page, "/alerts");
    await expect(page.getByRole("link", { name: /ALR-/ }).first()).toBeVisible();
    await page.getByRole("link", { name: /ALR-/ }).first().click();
    await expect(page).toHaveURL(/\/alerts\/ALR-/);
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
  });
});

test.describe("keyboard operation", () => {
  // Signed in once per test: the console's routes are gated, and a reload would
  // drop the in-memory token, so every navigation below stays in the app.
  test.beforeEach(async ({ page, api, signIn }) => {
    void api;
    await signIn(page, "/");
  });

  /**
   * Puts the tab order back at the start of the document.
   *
   * Signing in ends with a click, which leaves the sequential focus position
   * partway down the page. Reloading would reset it and also sign the operator
   * out, because the token is held in memory — so the focus is cleared instead.
   */
  async function resetFocus(page: Page): Promise<void> {
    await page.evaluate(() => {
      const active = document.activeElement;
      if (active instanceof HTMLElement) active.blur();
    });
  }

  test("exposes a skip link as the first tab stop", async ({ page }) => {
    await resetFocus(page);
    await page.keyboard.press("Tab");
    const focused = page.locator(":focus");
    await expect(focused).toHaveText(/skip to main content/i);
  });

  test("the skip link moves focus to the main landmark", async ({ page }) => {
    await resetFocus(page);
    await page.keyboard.press("Tab");
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(/#main-content$/);
  });

  test("primary navigation is reachable and operable by keyboard", async ({ page }) => {
    const alertsLink = page.getByRole("link", { name: "Alert queue" });
    await alertsLink.focus();
    await expect(alertsLink).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(/\/alerts$/);
  });

  test("focused controls have a visible focus indicator", async ({ page }) => {
    const link = page.getByRole("link", { name: "Alert queue" });

    // Reached by tabbing rather than by .focus(): a focus ring drawn with
    // :focus-visible is not applied to programmatic focus, so calling focus()
    // would test a state a keyboard user never reaches.
    await resetFocus(page);
    await page.keyboard.press("Tab");
    for (let i = 0; i < 15; i += 1) {
      if (await link.evaluate((el) => el === document.activeElement)) break;
      await page.keyboard.press("Tab");
    }
    await expect(link).toBeFocused();

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
    test(`${route.name} has no WCAG 2.1 A/AA violations`, async ({ page, api, signIn }) => {
      void api;
      if (route.session) await signIn(page, route.path);
      else await gotoSettled(page, route.path);

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
  test("does not scroll horizontally at tablet width", async ({ page, api, signIn }) => {
    void api;
    await page.setViewportSize({ width: 768, height: 1024 });
    await signIn(page, "/alerts");
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    );
    expect(overflow, "page body must not overflow horizontally").toBeLessThanOrEqual(1);
  });

  test("keeps the overview usable at desktop width", async ({ page, api, signIn }) => {
    void api;
    await page.setViewportSize({ width: 1440, height: 900 });
    await signIn(page, "/");
    await expect(page.getByRole("navigation", { name: /primary/i })).toBeVisible();
    await expect(page.getByRole("main")).toBeVisible();
  });
});
