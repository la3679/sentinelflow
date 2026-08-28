import { test as base, expect, type Page, type Route } from "@playwright/test";

/**
 * The API, stubbed in the browser.
 *
 * These run against the real production bundle with no backend anywhere — CI
 * builds the console and starts a static server, and nothing else. Stubbing at
 * the network boundary is what lets the console's real transport, real bearer
 * header and real 401 handling all be exercised without a Spring Boot service
 * and a database behind them, and it keeps the suite honest about which
 * requests the console actually makes: anything not routed here fails visibly
 * rather than quietly resolving from a fixture.
 */
export const OPERATOR = { username: "analyst.one", password: "a-demo-password" } as const;

/** Matches whatever origin the bundle was built to call. */
export const API_GLOB = "**/api/v1/**";

const TOKEN_BODY = {
  // Not a real JWT and not accepted by anything: three base64url segments with
  // no signature over them. The console treats a token as an opaque string, so
  // a value that only looks the part is enough to exercise every path here.
  token: "header.payload.not-a-signature",
  tokenType: "Bearer",
  expiresAt: "2099-01-01T00:00:00Z",
  roles: ["ANALYST"],
};

function problem(status: number, title: string, detail: string) {
  return {
    status,
    contentType: "application/problem+json",
    body: JSON.stringify({ type: "about:blank", title, status, detail }),
  };
}

export interface ApiStub {
  /** Every request the console sent to the API, newest last. */
  requests: { method: string; path: string; authorization: string | null }[];
  /** Make the next login attempt fail the way a wrong password does. */
  refuseLogin(): void;
  /** Make every authenticated request answer 401, the way an expired token does. */
  expireSession(): void;
}

interface Fixtures {
  api: ApiStub;
  signIn: (page: Page, landOn?: string) => Promise<void>;
}

// The second argument of a Playwright fixture is conventionally named `use`,
// which the React hooks lint rule reads as a call to React's `use`. Named
// `provide` here so the rule has nothing to misread.
export const test = base.extend<Fixtures>({
  api: async ({ page }, provide) => {
    const stub: ApiStub = {
      requests: [],
      refuseLogin() {
        loginRefused = true;
      },
      expireSession() {
        sessionExpired = true;
      },
    };
    let loginRefused = false;
    let sessionExpired = false;

    await page.route(API_GLOB, async (route: Route) => {
      const request = route.request();
      const path = new URL(request.url()).pathname;
      stub.requests.push({
        method: request.method(),
        path,
        authorization: await request.headerValue("authorization"),
      });

      if (path.endsWith("/auth/login")) {
        if (loginRefused) {
          await route.fulfill(
            problem(401, "Unauthorized", "The username and password were not accepted."),
          );
          return;
        }
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(TOKEN_BODY),
        });
        return;
      }

      if (sessionExpired) {
        await route.fulfill(problem(401, "Unauthorized", "The token has expired."));
        return;
      }

      await route.fulfill(problem(404, "Not Found", `Nothing stubbed for ${path}.`));
    });

    await provide(stub);
  },

  signIn: async ({ page }, provide) => {
    void page;
    await provide(async (target: Page, landOn = "/") => {
      await target.goto(landOn === "/" ? "/login" : `/login?next=${encodeURIComponent(landOn)}`);
      await target.getByLabel("Username").fill(OPERATOR.username);
      await target.getByLabel("Password").fill(OPERATOR.password);
      await target.getByRole("button", { name: "Sign in" }).click();
      // Compared as a path rather than matched as a pattern: building a regex
      // out of a route would need every slash escaping, and the thing being
      // waited for is an exact destination.
      await target.waitForURL((url) => url.pathname === landOn);

      // And then for the destination to have rendered. The URL changes before
      // React commits the new tree, and a test that acted on the gap would be
      // driving the sign-in screen while believing it was somewhere else. Sign
      // out is the signal because it exists only inside the signed-in shell.
      await expect(target.getByRole("button", { name: /sign out/i })).toBeVisible();
    });
  },
});

export { expect };
