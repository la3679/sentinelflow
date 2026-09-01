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
 *
 * **Every body below is the contract's shape**, field for field with
 * `contracts/openapi/sentinelflow-api.yaml`. A stub that answered a shape the
 * API does not serve would let a screen pass here and break against the real
 * one, which is the whole failure mode this suite exists to prevent.
 */
export const OPERATOR = { username: "analyst.one", password: "a-demo-password" } as const;

/** Matches whatever origin the bundle was built to call. */
export const API_GLOB = "**/api/v1/**";

/** Who the stub signs in as, so "assign to me" has somebody to be. */
export const SIGNED_IN_OPERATOR_ID = "44444444-4444-4444-a444-444444444444";

/** Another operator, so the picker has a choice that is not the caller. */
export const OTHER_OPERATOR_ID = "55555555-5555-4555-a555-555555555555";
export const OTHER_OPERATOR_NAME = "B. Analyst";

/**
 * The operator directory, in the contract's shape.
 *
 * An auditor is deliberately absent: `GET /operators` lists only operators who
 * may hold an alert, and a stub that returned one would be describing an API
 * that does not exist.
 */
const OPERATORS = [
  {
    operatorId: SIGNED_IN_OPERATOR_ID,
    username: "analyst.one",
    displayName: "A. Analyst",
    roles: ["ANALYST"],
  },
  {
    operatorId: OTHER_OPERATOR_ID,
    username: "analyst.two",
    displayName: OTHER_OPERATOR_NAME,
    roles: ["ANALYST"],
  },
];

const TOKEN_BODY = {
  // Not a real JWT and not accepted by anything: three base64url segments with
  // no signature over them. The console treats a token as an opaque string, so
  // a value that only looks the part is enough to exercise every path here.
  token: "header.payload.not-a-signature",
  tokenType: "Bearer",
  expiresAt: "2099-01-01T00:00:00Z",
  operatorId: SIGNED_IN_OPERATOR_ID,
  displayName: "A. Analyst",
  roles: ["ANALYST"],
};

export const ALERT_ID = "11111111-1111-4111-a111-111111111111";
export const ALERT_REFERENCE = "ALT-0001";
export const TRANSACTION_ID = "22222222-2222-4222-a222-222222222222";
export const TRANSACTION_REFERENCE = "TXN-000042";
const ASSESSMENT_ID = "33333333-3333-4333-a333-333333333333";

function problem(status: number, title: string, detail: string, extras: object = {}) {
  return {
    status,
    contentType: "application/problem+json",
    body: JSON.stringify({ type: "about:blank", title, status, detail, ...extras }),
  };
}

function json(body: unknown) {
  return { status: 200, contentType: "application/json", body: JSON.stringify(body) };
}

export interface ApiStub {
  /** Every request the console sent to the API, newest last. */
  requests: { method: string; path: string; authorization: string | null }[];
  /** Make the next login attempt fail the way a wrong password does. */
  refuseLogin(): void;
  /** Make every authenticated request answer 401, the way an expired token does. */
  expireSession(): void;
  /**
   * Make the next transition answer 409 with a stale version, and move the
   * alert on — the way a second analyst acting first looks from here.
   */
  conflictOnNextTransition(): void;
  /**
   * Answer the model screen the way the API does when scoring is unreachable:
   * the policy half present, the model half null, and a reason.
   */
  loseTheScoringService(): void;
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
      conflictOnNextTransition() {
        conflictArmed = true;
      },
      loseTheScoringService() {
        modelAvailable = false;
      },
    };
    let loginRefused = false;
    let sessionExpired = false;
    let conflictArmed = false;
    let modelAvailable = true;

    /** The one alert this stub serves, which a transition moves. */
    const alert = {
      alertId: ALERT_ID,
      alertReference: ALERT_REFERENCE,
      transactionId: TRANSACTION_ID,
      assessmentId: ASSESSMENT_ID,
      status: "NEW",
      priority: "URGENT",
      assigneeId: null as string | null,
      assignee: null as { operatorId: string; username: string; displayName: string } | null,
      summary: "HIGH risk 82 on TXN-000042 — R_VELOCITY_10M",
      riskBand: "HIGH",
      finalScore: 82,
      version: 0,
      legalTargets: ["IN_REVIEW"],
      createdAt: "2026-08-28T09:00:00Z",
      updatedAt: "2026-08-28T09:00:00Z",
      closedAt: null as string | null,
    };

    const transaction = {
      transactionId: TRANSACTION_ID,
      transactionReference: TRANSACTION_REFERENCE,
      accountReference: "ACC-000045",
      merchantReference: "MER-0042",
      merchantCategoryCode: "5411",
      type: "PURCHASE",
      channel: "CARD_NOT_PRESENT",
      amount: { value: "1249.9900", currency: "GBP" },
      originCountry: "GB",
      occurredAt: "2026-08-28T08:59:00Z",
      ingestedAt: "2026-08-28T08:59:01Z",
      processingStatus: "ASSESSED",
      riskBand: "HIGH",
    };

    const assessment = {
      assessmentId: ASSESSMENT_ID,
      transactionId: TRANSACTION_ID,
      ruleScore: 50,
      modelScore: 91,
      finalScore: 82,
      riskBand: "HIGH",
      degraded: false,
      modelVersion: "1.0.0",
      featureVersion: "1.0.0",
      rulesetVersion: "1.0.0",
      policyVersion: "1.1.0",
      reasonCodes: [
        {
          code: "R_VELOCITY_10M",
          description: "Five attempts on this account within ten minutes.",
          contribution: 25,
          source: "RULE",
        },
      ],
      scoringLatencyMs: 42,
      assessedAt: "2026-08-28T08:59:03Z",
    };

    const alertSummary = {
      from: "2026-08-21T09:00:00Z",
      to: "2026-08-28T09:00:00Z",
      total: 8,
      open: 6,
      closed: 2,
      byStatus: {
        NEW: 3,
        IN_REVIEW: 2,
        ESCALATED: 1,
        CONFIRMED_SUSPICIOUS: 0,
        DISMISSED_FALSE_POSITIVE: 0,
        CLOSED: 2,
      },
      byPriority: { LOW: 1, MEDIUM: 3, HIGH: 2, URGENT: 2 },
      byRiskBand: { LOW: 0, MEDIUM: 1, HIGH: 5, CRITICAL: 2 },
    };

    const modelMetadata = {
      modelVersion: "1.0.0",
      featureVersion: "1.0.0",
      policyVersion: "1.1.0",
      algorithm: "gradient-boosting",
      trainedAt: "2026-07-19T08:30:00Z",
      artifactSha256: "9f2c1b7a4e6d8c0f3a5b7d9e1c3a5b7d9e1c3a5b7d9e1c3a5b7d9e1c3a5b7d9e",
      modelAvailable: true,
      modelUnavailableReason: null as string | null,
      metrics: {
        precision: 0.82,
        recall: 0.61,
        f1: 0.7,
        averagePrecision: 0.74,
        falsePositiveRate: 0.012,
        operatingThreshold: 62.5,
      } as object | null,
      thresholds: [
        { riskBand: "LOW", minFinalScore: 0, raisesAlert: false, priority: null },
        { riskBand: "MEDIUM", minFinalScore: 40, raisesAlert: false, priority: null },
        { riskBand: "HIGH", minFinalScore: 70, raisesAlert: true, priority: "HIGH" },
        { riskBand: "CRITICAL", minFinalScore: 90, raisesAlert: true, priority: "URGENT" },
      ],
      limitations: [
        "Every figure here describes a synthetic demonstration model. No production or real-world performance is represented.",
        "A score is not a determination of fraud. It is an ordering signal for human review.",
        "The model's own operating threshold is a recommendation. What runs is the policy below.",
        "Thresholds are read-only here.",
      ],
    };

    /** The same body with the scoring service unreachable, which is a state the screen must survive. */
    const withoutTheModel = (full: typeof modelMetadata) => ({
      ...full,
      modelVersion: null,
      featureVersion: null,
      algorithm: null,
      trainedAt: null,
      artifactSha256: null,
      modelAvailable: false,
      modelUnavailableReason: "Scoring could not be reached for /v1/model",
      metrics: null,
    });

    const systemHealth = {
      components: [
        {
          componentId: "api",
          name: "Operations API",
          state: "OPERATIONAL",
          detail: "Answering requests, which is how you are reading this.",
        },
        {
          componentId: "database",
          name: "PostgreSQL",
          state: "OPERATIONAL",
          detail: "A connection was borrowed and a query answered.",
        },
        {
          componentId: "scoring",
          name: "Scoring service",
          state: "OUTAGE",
          detail: "Not answering. Assessments continue on the rules alone and are marked degraded.",
        },
      ],
      checkedAt: "2026-08-28T09:10:00Z",
    };

    const page0 = (content: unknown[]) => ({
      content,
      page: { page: 0, size: 20, totalElements: content.length, totalPages: 1 },
    });

    /**
     * A queue with enough in it to be a queue.
     *
     * The single alert above is the one every detail and transition test acts
     * on. A list of exactly one would still pass those, and would make the
     * README's screenshot of the queue a picture of an empty morning — so the
     * rest of the page is filled deterministically, in the server's own order.
     */
    const QUEUE_PADDING = ["URGENT", "HIGH", "HIGH", "MEDIUM", "MEDIUM", "MEDIUM", "LOW"].map(
      (priority, index) => ({
        ...alert,
        alertId: `a1a1a1a1-1111-4111-a111-${String(index + 10).padStart(12, "0")}`,
        alertReference: `ALT-${String(index + 2).padStart(4, "0")}`,
        status: ["NEW", "IN_REVIEW", "ESCALATED", "NEW", "IN_REVIEW", "NEW", "CLOSED"][index],
        priority,
        assigneeId: index % 2 === 0 ? null : OTHER_OPERATOR_ID,
        assignee:
          index % 2 === 0
            ? null
            : {
                operatorId: OTHER_OPERATOR_ID,
                username: "analyst.two",
                displayName: OTHER_OPERATOR_NAME,
              },
        riskBand: priority === "URGENT" ? "CRITICAL" : priority === "LOW" ? "MEDIUM" : "HIGH",
        finalScore: 92 - index * 4,
        summary: `${priority === "URGENT" ? "CRITICAL" : "HIGH"} risk ${92 - index * 4} on TXN-0000${index + 43} — ${
          ["R_VELOCITY_10M", "R_GEO_MISMATCH", "R_UNKNOWN_DEVICE", "R_BALANCE_DRAIN"][index % 4]
        }`,
        createdAt: `2026-08-28T0${index}:12:00Z`,
        legalTargets: [],
      }),
    );

    await page.route(API_GLOB, async (route: Route) => {
      const request = route.request();
      const path = new URL(request.url()).pathname;
      stub.requests.push({
        method: request.method(),
        path,
        authorization: await request.headerValue("authorization"),
      });

      if (path.endsWith("/operators") || path.includes("/operators?")) {
        await route.fulfill(
          json({
            content: OPERATORS,
            page: { page: 0, size: 200, totalElements: OPERATORS.length, totalPages: 1 },
          }),
        );
        return;
      }

      if (path.endsWith("/auth/login")) {
        if (loginRefused) {
          await route.fulfill(
            problem(401, "Unauthorized", "The username and password were not accepted."),
          );
          return;
        }
        await route.fulfill(json(TOKEN_BODY));
        return;
      }

      if (sessionExpired) {
        await route.fulfill(problem(401, "Unauthorized", "The token has expired."));
        return;
      }

      if (path.endsWith(`/alerts/${ALERT_ID}/transition`)) {
        if (conflictArmed) {
          conflictArmed = false;
          // Somebody else acted first: the alert has moved on, and the version
          // the console held is stale. The problem body carries what it is now,
          // so the console can re-read and offer the move again.
          alert.status = "IN_REVIEW";
          alert.version = 1;
          alert.legalTargets = ["ESCALATED", "CONFIRMED_SUSPICIOUS", "DISMISSED_FALSE_POSITIVE"];
          await route.fulfill(
            problem(409, "Version conflict", "This alert has changed since you read it.", {
              expectedVersion: 0,
              currentVersion: alert.version,
            }),
          );
          return;
        }
        const body = JSON.parse(request.postData() ?? "{}") as { targetStatus?: string };
        alert.status = body.targetStatus ?? alert.status;
        alert.version += 1;
        alert.legalTargets = ["ESCALATED", "CONFIRMED_SUSPICIOUS", "DISMISSED_FALSE_POSITIVE"];
        await route.fulfill(json(alert));
        return;
      }

      if (path.endsWith(`/alerts/${ALERT_ID}/assignment`)) {
        // One operation for both directions, as the contract says: a null
        // assigneeId releases the alert rather than assigning it to nobody. The
        // stub resolves the identifier the way the API does, so the console is
        // exercised against the shape it will actually receive.
        const body = JSON.parse(request.postData() ?? "{}") as { assigneeId?: string | null };
        const assigneeId = body.assigneeId ?? null;
        alert.assigneeId = assigneeId;
        alert.assignee = OPERATORS.find((operator) => operator.operatorId === assigneeId) ?? null;
        alert.version += 1;
        await route.fulfill(json(alert));
        return;
      }

      if (path.endsWith(`/alerts/${ALERT_ID}/history`)) {
        await route.fulfill(
          json(
            page0([
              {
                actionId: "44444444-4444-4444-a444-444444444444",
                alertId: ALERT_ID,
                actorId: "55555555-5555-4555-a555-555555555555",
                actorRole: "SYSTEM",
                actionType: "CREATED",
                previousStatus: null,
                newStatus: "NEW",
                note: null,
                occurredAt: "2026-08-28T09:00:00Z",
              },
            ]),
          ),
        );
        return;
      }

      if (path.endsWith(`/alerts/${ALERT_ID}/notes`)) {
        await route.fulfill({
          status: 201,
          contentType: "application/json",
          body: JSON.stringify({
            actionId: "66666666-6666-4666-a666-666666666666",
            alertId: ALERT_ID,
            actorId: "55555555-5555-4555-a555-555555555555",
            actorRole: "ANALYST",
            actionType: "NOTE_ADDED",
            note: "Recorded by the end-to-end suite.",
            occurredAt: "2026-08-28T09:05:00Z",
          }),
        });
        return;
      }

      if (path.endsWith(`/alerts/${ALERT_ID}`)) {
        await route.fulfill(json(alert));
        return;
      }

      if (path.endsWith("/alerts")) {
        await route.fulfill(json(page0([alert, ...QUEUE_PADDING])));
        return;
      }

      if (path.endsWith(`/transactions/${TRANSACTION_ID}/assessment`)) {
        await route.fulfill(json(assessment));
        return;
      }

      if (path.endsWith(`/transactions/${TRANSACTION_ID}`)) {
        await route.fulfill(json(transaction));
        return;
      }

      if (path.endsWith("/transactions")) {
        await route.fulfill(json(page0([transaction])));
        return;
      }

      if (path.endsWith("/reports/alert-summary")) {
        await route.fulfill(json(alertSummary));
        return;
      }

      if (path.endsWith("/reports/alerts.csv")) {
        await route.fulfill({
          status: 200,
          contentType: "text/csv;charset=UTF-8",
          body: "alertReference,status,priority\nALT-0001,NEW,URGENT\n",
        });
        return;
      }

      if (path.endsWith("/models/active")) {
        await route.fulfill(json(modelAvailable ? modelMetadata : withoutTheModel(modelMetadata)));
        return;
      }

      if (path.endsWith("/system/health")) {
        await route.fulfill(json(systemHealth));
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
