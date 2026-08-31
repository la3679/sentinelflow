import AxeBuilder from "@axe-core/playwright";
import type { Page } from "@playwright/test";

import { ALERT_ID, ALERT_REFERENCE, expect, test } from "./fixtures";

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
  test("the login carries no token and everything after it does", async ({ page, api, signIn }) => {
    await signIn(page, "/alerts");
    // Navigated in the application rather than reloaded: a reload drops the
    // in-memory token, which is a different test.
    await page.getByRole("link", { name: "Operations overview" }).click();
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();

    const paths = api.requests.map((r) => `${r.method} ${r.path}`);
    expect(paths[0]).toBe("POST /api/v1/auth/login");
    expect(api.requests[0]?.authorization, "a login carries no bearer token").toBeNull();

    // The queue is a real request now. The overview is not: its endpoint has no
    // server counterpart yet, and the four that do not are listed in
    // docs/frontend/API_MIGRATION_AUDIT.md. When they gain one this expectation
    // changes, rather than the console quietly starting to make requests.
    expect(paths).toContain("GET /api/v1/alerts");
    expect(paths).not.toContain("GET /api/v1/overview");

    for (const request of api.requests.slice(1)) {
      expect(request.authorization, `${request.path} carries the bearer token`).toBe(
        "Bearer header.payload.not-a-signature",
      );
    }
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

  // The sign-in form's validation is a schema (zod) behind a resolver
  // (react-hook-form), and neither is exercised by a submission that happens to
  // be well formed. This drives the refusal itself, so a major version of
  // either has to keep the messages rendering and keep the request unsent.
  test("an empty sign-in is refused by the form, and never reaches the API", async ({
    page,
    api,
  }) => {
    await page.goto("/login");
    await page.getByRole("button", { name: "Sign in" }).click();

    await expect(page.locator("#username-error")).toHaveText("Enter your username.");
    await expect(page.locator("#password-error")).toHaveText("Enter your password.");
    await expect(page.getByLabel("Username")).toHaveAttribute("aria-invalid", "true");
    await expect(page.getByLabel("Password")).toHaveAttribute("aria-invalid", "true");

    expect(api.requests.map((request) => `${request.method} ${request.path}`)).not.toContain(
      "POST /api/v1/auth/login",
    );
  });

  test("renders alert rows from the API, by their references", async ({ page, api, signIn }) => {
    void api;
    await signIn(page, "/alerts");
    // The queue resolves on a delay; wait for a row rather than counting a
    // skeleton. The reference is what a person reads; the identifier is what
    // the link routes on (ADR-0007).
    await expect(page.getByText(ALERT_REFERENCE).first()).toBeVisible();
    expect(await page.getByRole("row").count()).toBeGreaterThan(1);
  });

  test("opens an alert detail from the queue", async ({ page, api, signIn }) => {
    void api;
    await signIn(page, "/alerts");
    await page.getByRole("link", { name: ALERT_REFERENCE }).first().click();
    await expect(page).toHaveURL(new RegExp(`/alerts/${ALERT_ID}$`));
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
  });

  test("offers only the moves the alert says this reader may make", async ({
    page,
    api,
    signIn,
  }) => {
    void api;
    await signIn(page, `/alerts/${ALERT_ID}`);

    // Rendered from legalTargets and nothing else. The console used to hold a
    // second copy of the state machine that offered two moves the server
    // answers 409 to and hid four legal ones.
    await expect(page.getByRole("button", { name: "Move to In review" })).toBeVisible();
    await expect(page.getByRole("button", { name: /move to closed/i })).toHaveCount(0);
  });

  test("a transition answers 409 by re-reading the alert and offering the move again", async ({
    page,
    api,
    signIn,
  }) => {
    await signIn(page, `/alerts/${ALERT_ID}`);
    api.conflictOnNextTransition();

    await page.getByRole("button", { name: "Move to In review" }).click();

    // Not a toast saying "conflict": the alert is re-read, what it is now is
    // stated, and the move is offered again at the version that came back.
    const notice = page
      .getByRole("alert")
      .filter({ hasText: /changed while you were reading it/i });
    await expect(notice).toBeVisible();
    await expect(notice).toContainText(/in review/i);
  });
});

test.describe("the screens that describe the platform", () => {
  test("the model screen shows the policy that runs, not just the model", async ({
    page,
    api,
    signIn,
  }) => {
    void api;
    await signIn(page, "/model");

    await expect(page.getByText("gradient-boosting")).toBeVisible();
    // The policy half is the part an analyst acts on: which band opens an
    // alert, and at what priority.
    await expect(
      page.getByRole("cell", { name: /no — scored and stored only/i }).first(),
    ).toBeVisible();
    await expect(page.getByText(/urgent priority/i).first()).toBeVisible();
  });

  test("the model screen keeps the policy when the scoring service is gone", async ({
    page,
    api,
    signIn,
  }) => {
    api.loseTheScoringService();
    await signIn(page, "/model");

    // The two halves have different owners and either can be missing without
    // the other being wrong. Blanking this screen during a scoring outage would
    // hide exactly what somebody would be looking for.
    await expect(
      page.getByText("The scoring service is not answering.", { exact: true }),
    ).toBeVisible();
    await expect(page.getByText(/the policy that runs/i)).toBeVisible();
    await expect(page.getByRole("cell", { name: "70" })).toBeVisible();
  });

  test("the health screen says what is not measured rather than inventing it", async ({
    page,
    api,
    signIn,
  }) => {
    void api;
    await signIn(page, "/health");

    await expect(page.getByText(/operations api/i)).toBeVisible();
    await expect(page.getByText(/not answering/i).first()).toBeVisible();
    // Consumer lag and dead-letter depth used to be shown here as figures
    // nothing had measured.
    await expect(page.getByText(/not measured yet, so not shown/i)).toBeVisible();
  });

  test("the reports screen counts a window and offers the same window as a file", async ({
    page,
    api,
    signIn,
  }) => {
    void api;
    await signIn(page, "/reports");

    await expect(page.getByText(/alerts raised/i).first()).toBeVisible();
    await expect(page.getByRole("button", { name: /download this window as csv/i })).toBeEnabled();
  });

  test("the overview composes two requests and asks for no aggregate endpoint", async ({
    page,
    api,
    signIn,
  }) => {
    await signIn(page, "/");
    await expect(page.getByText(/next in the queue/i)).toBeVisible();

    const paths = api.requests.map((r) => r.path);
    expect(paths.some((path) => path.endsWith("/reports/alert-summary"))).toBe(true);
    expect(paths.some((path) => path.endsWith("/alerts"))).toBe(true);
    // ADR-0014 §3: an aggregate would be a second implementation of risk-band
    // counting beside the report that already does it.
    expect(paths.some((path) => path.endsWith("/overview"))).toBe(false);
  });

  // The two charts are the only third-party rendering in the console, and a
  // charting major can stop drawing without throwing: the route still renders,
  // the console stays quiet, and the panel is simply empty. These assert the
  // bars exist, so a bump that produces an empty <svg> fails here rather than
  // in a screenshot nobody takes.
  for (const chart of [
    { path: "/", label: "Alerts raised per risk band over the last day" },
    { path: "/reports", label: "Alert count per risk band over the selected window" },
  ]) {
    test(`the risk-band chart on ${chart.path} draws bars from the data`, async ({
      page,
      api,
      signIn,
    }) => {
      void api;
      await signIn(page, chart.path);

      const figure = page.getByRole("img", { name: chart.label });
      await expect(figure).toBeVisible();
      await expect(figure.locator("svg.recharts-surface")).toBeVisible();
      // One rectangle per risk band the window contains; the count is the
      // stub's, so this asserts the data reached the chart rather than that
      // some SVG exists.
      await expect
        .poll(async () => await figure.locator(".recharts-bar-rectangle").count())
        .toBeGreaterThan(0);
    });
  }
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
