import { expect, test, type Page } from "@playwright/test";

/**
 * Operator identity, verified against the Docker Compose stack.
 *
 * `PROJECT_STATE.md`, "Required before v1" §1 makes one clause binding that no
 * other suite in this repository can satisfy: operator identity must be
 * **verified against the real stack, not only against Testcontainers**. The
 * console suite in `tests/e2e` stubs the API in the browser, and the API suite
 * runs against Testcontainers. Both were green on code that could not start
 * under compose, which is how three defects reached a demo unnoticed.
 *
 * So everything below goes through the console image compose publishes, to the
 * API image compose publishes, to the PostgreSQL it publishes. There is no
 * stub, no fixture and no seeding: the operators are whoever the stack holds,
 * and the alert is whichever assignable one the queue offers first.
 */

const API_BASE = process.env["SENTINELFLOW_API_URL"] ?? "http://localhost:8080/api/v1";

/**
 * The password the seeded demo operators share.
 *
 * From the environment only. `make verify-real-stack` and
 * `.\scripts\dev\sf.ps1 verify-real-stack` put it there out of the git-ignored
 * `.env`, the same way the `bench` target already does, so a developer with a
 * working stack does not have to export it a second time.
 *
 * **Read here rather than parsed here.** An earlier version opened `.env`
 * itself, and CodeQL was right about what that is: file contents flowing into
 * an outbound request (`js/file-access-to-http`). A test has no business
 * implementing dotenv, and moving the read to the runner removes the flow
 * instead of arguing about it.
 */
const PASSWORD = process.env["SENTINELFLOW_DEMO_OPERATOR_PASSWORD"] ?? "";

interface Operator {
  operatorId: string;
  username: string;
  displayName: string;
  roles: string[];
}

interface Alert {
  alertId: string;
  alertReference: string;
  status: string;
  version: number;
  assigneeId: string | null;
  assignee: { operatorId: string; username: string; displayName: string } | null;
}

async function api<T>(path: string, token: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
      ...(init.headers ?? {}),
    },
  });
  if (!response.ok) {
    throw new Error(`${init.method ?? "GET"} ${path} answered ${response.status}`);
  }
  return (await response.json()) as T;
}

interface Login {
  token: string;
  operatorId: string;
  displayName: string;
  roles: string[];
}

/**
 * One session per operator, for the whole run.
 *
 * `/auth/login` is rate-limited to ten attempts a minute per caller
 * (ADR-0017 §2), and every sign-in here shares one caller because they all come
 * from this machine. An earlier version signed in on demand and a full run
 * spent its allowance partway through the last test — a 429 that is the
 * limiter working correctly and this suite behaving badly.
 */
const sessions = new Map<string, Login>();

async function signInThroughTheApi(username: string): Promise<Login> {
  const cached = sessions.get(username);
  if (cached) return cached;

  let response = await postLogin(username);
  if (response.status === 429) {
    // What a well-behaved client does with the header the API sends, rather
    // than a fixed sleep that would be a guess about somebody else's limit.
    const after = Number(response.headers.get("Retry-After") ?? "60");
    await new Promise((wake) => setTimeout(wake, (Number.isFinite(after) ? after : 60) * 1000));
    response = await postLogin(username);
  }
  if (!response.ok) throw new Error(`login as ${username} answered ${response.status}`);

  const session = (await response.json()) as Login;
  sessions.set(username, session);
  return session;
}

function postLogin(username: string): Promise<Response> {
  return fetch(`${API_BASE}/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password: PASSWORD }),
  });
}

/**
 * Signs in through the console's own form, as an operator would.
 *
 * Not shared between tests and not replaced by seeding a token: the token lives
 * in the tab's memory and nowhere else (ADR-0012 §3), so there is nothing to
 * carry across and a `page.goto` mid-journey would sign the operator out. Each
 * test therefore spends one of the ten logins a minute the limiter allows,
 * which is why the API-side sessions above are cached rather than repeated.
 */
async function signInThroughTheConsole(page: Page, landOn: string): Promise<void> {
  await page.goto(`/login?next=${encodeURIComponent(landOn)}`);
  await page.getByLabel("Username").fill("analyst.one");
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();

  // Say which of the two things went wrong rather than timing out on the
  // signed-in shell. A refusal here is nearly always the login limiter, and a
  // bare timeout sends the reader looking at the console instead.
  const refused = page.getByRole("alert");
  await Promise.race([
    page.waitForURL((url) => url.pathname === landOn),
    refused.waitFor({ state: "visible" }).then(async () => {
      throw new Error(
        `the console refused the sign-in: "${await refused.innerText()}". ` +
          "Ten logins a minute per caller is the limit (ADR-0017 §2); wait a minute and rerun.",
      );
    }),
  ]);
  await expect(page.getByRole("button", { name: /sign out/i })).toBeVisible();
}

/**
 * Every alert this run assigned, released again afterwards.
 *
 * A failing test used to leave one held, and the next test then picked a
 * different alert or none at all — so one real failure arrived as several, and
 * a rerun started from somewhere the first run had moved. Cleanup belongs in a
 * hook rather than at the end of each test for exactly that reason.
 */
const touched = new Set<string>();

/**
 * An alert this suite may move: open, and not already held by somebody.
 *
 * Chosen from the queue rather than written by the test, because a test that
 * creates its own row is not exercising the data the stack actually serves.
 */
async function anAssignableAlert(token: string): Promise<Alert> {
  const queue = await api<{ content: Alert[] }>("/alerts?size=50&status=NEW", token);
  const free = queue.content.find((alert) => alert.assigneeId === null);
  if (!free) throw new Error("no unassigned NEW alert on the stack to work with");
  touched.add(free.alertId);
  return free;
}

/** Puts an alert back where the suite found it, so a rerun starts level. */
async function release(alertId: string, token: string): Promise<void> {
  const current = await api<Alert>(`/alerts/${alertId}`, token);
  if (current.assigneeId === null) return;
  await api<Alert>(`/alerts/${alertId}/assignment`, token, {
    method: "PUT",
    body: JSON.stringify({ assigneeId: null, expectedVersion: current.version }),
  });
}

test.describe("operator identity, against the real stack", () => {
  test.skip(
    !PASSWORD,
    "SENTINELFLOW_DEMO_OPERATOR_PASSWORD is not set. Run this through `make verify-real-stack` " +
      "or `.\\scripts\\dev\\sf.ps1 verify-real-stack`, which read it out of .env.",
  );

  let session: Login;
  let token: string;
  let operators: Operator[];

  test.beforeAll(async () => {
    session = await signInThroughTheApi("analyst.one");
    token = session.token;
    operators = (await api<{ content: Operator[] }>("/operators?size=200", token)).content;
  });

  test.afterEach(async () => {
    for (const alertId of touched) await release(alertId, token);
    touched.clear();
  });

  test("the login response carries the operator's own identity, which is what makes 'assign to me' buildable", async () => {
    expect(session.operatorId).toMatch(/^[0-9a-f-]{36}$/);
    expect(session.displayName).not.toBe("");
    // And it is the same person the directory lists, rather than a second
    // identifier that happens to be a UUID.
    const self = operators.find((operator) => operator.operatorId === session.operatorId);
    expect(self?.username).toBe("analyst.one");
  });

  test("the picker offers the operators the API actually holds, and nobody invented", async ({
    page,
  }) => {
    const alert = await anAssignableAlert(token);
    await signInThroughTheConsole(page, `/alerts/${alert.alertId}`);
    await expect(page.getByText(/unassigned/i).first()).toBeVisible();

    await page.getByLabel(/assign to/i).click();
    const offered = await page.getByRole("option").allInnerTexts();

    // Every option is somebody the directory returned. A hardcoded UUID or an
    // invented user to pad the list would fail here rather than look plausible.
    const known = new Set(operators.map((operator) => operator.displayName));
    expect(offered.length).toBeGreaterThan(0);
    for (const option of offered) {
      const name = (option.split("—")[0] ?? option).trim();
      expect([...known], `the picker offered "${name}", who the API does not list`).toContain(name);
    }

    // The alert is unassigned, so everybody the directory holds is offered.
    expect(offered.length).toBe(operators.length);
    await page.keyboard.press("Escape");
  });

  test("an analyst gives an alert to a named operator, and the API agrees it happened", async ({
    page,
  }) => {
    const alert = await anAssignableAlert(token);
    const target = operators.find((operator) => operator.username === "analyst.two");
    expect(target, "analyst.two is seeded on every stack").toBeDefined();

    await signInThroughTheConsole(page, `/alerts/${alert.alertId}`);
    await page.getByLabel(/assign to/i).click();
    await page.getByRole("option", { name: new RegExp(target!.displayName, "i") }).click();
    await page.getByRole("button", { name: /^assign$/i }).click();

    // A person, not a UUID.
    await expect(page.getByText("Held by")).toBeVisible();
    await expect(page.getByText(target!.displayName, { exact: true })).toBeVisible();

    // And the server holds what the screen claims, read back independently of
    // the console's own cache. This is the assertion the stubbed suite cannot
    // make, because there is nothing behind it to disagree.
    const stored = await api<Alert>(`/alerts/${alert.alertId}`, token);
    expect(stored.assigneeId).toBe(target!.operatorId);
    expect(stored.assignee?.displayName).toBe(target!.displayName);
    expect(stored.version).toBe(alert.version + 1);

    // The queue renders the same resolved person. Navigated in the app: the
    // token lives in the tab's memory, so a reload would sign the operator out.
    await page.getByRole("link", { name: /back to queue/i }).click();
    await expect(page.getByRole("heading", { level: 1 })).toHaveText(/alert queue/i);
    await expect(page.getByText(target!.displayName).first()).toBeVisible();
  });

  test("a lost race is reported to the operator rather than silently overwritten", async ({
    page,
  }) => {
    const alert = await anAssignableAlert(token);
    const target = operators.find((operator) => operator.username === "analyst.two")!;

    await signInThroughTheConsole(page, `/alerts/${alert.alertId}`);
    await expect(page.getByLabel(/assign to/i)).toBeVisible();

    // Somebody else takes it while this screen is open. The console is now
    // holding a version the server has moved past.
    const other = await signInThroughTheApi("administrator.one");
    await api<Alert>(`/alerts/${alert.alertId}/assignment`, other.token, {
      method: "PUT",
      body: JSON.stringify({
        assigneeId: other.operatorId,
        expectedVersion: alert.version,
      }),
    });

    await page.getByLabel(/assign to/i).click();
    await page.getByRole("option", { name: new RegExp(target.displayName, "i") }).click();
    await page.getByRole("button", { name: /^assign$/i }).click();

    // Told what happened, and told who holds it now — rendered from the re-read
    // alert rather than from the problem body.
    const notice = page
      .getByRole("alert")
      .filter({ hasText: /changed while you were reading it/i });
    await expect(notice).toBeVisible();
    await expect(notice).toContainText(other.displayName);
    await expect(notice).toContainText(new RegExp(`Assigning it to ${target.displayName}`, "i"));

    // And nothing was overwritten: the server still holds the other operator.
    const stored = await api<Alert>(`/alerts/${alert.alertId}`, token);
    expect(stored.assigneeId).toBe(other.operatorId);
  });

  test("the server refuses an assignment the console would never offer", async () => {
    const alert = await anAssignableAlert(token);

    // The picker cannot offer an auditor, because `GET /operators` does not
    // list one. Server-side authorization is what makes that a rule rather than
    // a user-experience affordance, so it is asserted here directly.
    const auditor = await signInThroughTheApi("auditor.one");
    expect(operators.map((operator) => operator.operatorId)).not.toContain(auditor.operatorId);

    const assigningAnAuditor = await fetch(`${API_BASE}/alerts/${alert.alertId}/assignment`, {
      method: "PUT",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
      body: JSON.stringify({ assigneeId: auditor.operatorId, expectedVersion: alert.version }),
    });
    expect(assigningAnAuditor.status).toBe(422);

    // And an auditor cannot assign at all, whatever a client sends.
    const auditorAssigning = await fetch(`${API_BASE}/alerts/${alert.alertId}/assignment`, {
      method: "PUT",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${auditor.token}` },
      body: JSON.stringify({
        assigneeId: operators[0]!.operatorId,
        expectedVersion: alert.version,
      }),
    });
    expect(auditorAssigning.status).toBe(403);

    // A stale version is refused too, which is what the console's conflict
    // notice is built on.
    const held = await signInThroughTheApi("analyst.two");
    await api<Alert>(`/alerts/${alert.alertId}/assignment`, token, {
      method: "PUT",
      body: JSON.stringify({ assigneeId: held.operatorId, expectedVersion: alert.version }),
    });
    const stale = await fetch(`${API_BASE}/alerts/${alert.alertId}/assignment`, {
      method: "PUT",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
      body: JSON.stringify({ assigneeId: null, expectedVersion: alert.version }),
    });
    expect(stale.status).toBe(409);
    const problem = (await stale.json()) as { currentVersion: number; expectedVersion: number };
    expect(problem.currentVersion).toBe(alert.version + 1);
    expect(problem.expectedVersion).toBe(alert.version);
  });
});
