import { describe, expect, it, vi } from "vitest";

/**
 * The token is a credential, and it lives in memory or nowhere.
 *
 * `docs/development/ENGINEERING_STANDARDS.md` forbids session or authorization state in browser
 * storage. This was a scope guard while the console had no session at all; now
 * that it holds a real bearer token it is the thing that stops a reload-survives
 * convenience from becoming a credential on disk. A reload signing the operator
 * out is the intended consequence, not a defect to work around.
 */
describe("browser storage", () => {
  it("is not written when a real session is established", async () => {
    const localSet = vi.spyOn(Storage.prototype, "setItem");

    const { makeStore, signedIn } = await import("@/store");
    const store = makeStore();
    store.dispatch(
      signedIn({
        username: "analyst.one",
        token: "a.jwt.value",
        tokenType: "Bearer",
        expiresAt: "2026-08-28T20:00:00Z",
        operatorId: "11111111-1111-4111-a111-111111111111",
        displayName: "A. Analyst",
        roles: ["ANALYST"],
      }),
    );

    expect(store.getState().session.token).toBe("a.jwt.value");
    expect(localSet).not.toHaveBeenCalled();
    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);

    localSet.mockRestore();
  });

  it("has no source file referencing web storage APIs", async () => {
    const { readdirSync, readFileSync } = await import("node:fs");
    const { join } = await import("node:path");

    const offenders: string[] = [];
    const walk = (dir: string): void => {
      // `withFileTypes` rather than a `statSync` per entry. The stat version
      // asks the filesystem a second time about a path it has already been told
      // about, which is a time-of-check/time-of-use gap CodeQL reports as
      // `js/file-system-race` - and it is a fair report even here, where the
      // only writer is the developer's own editor. The directory entry already
      // carries the answer.
      for (const entry of readdirSync(dir, { withFileTypes: true })) {
        const full = join(dir, entry.name);
        if (entry.isDirectory()) {
          walk(full);
        } else if (/\.tsx?$/.test(entry.name)) {
          const source = readFileSync(full, "utf8");
          // Match real calls, not the prose in doc comments explaining the rule.
          if (/\b(localStorage|sessionStorage)\s*\.\s*(get|set|remove)Item\b/.test(source)) {
            offenders.push(full);
          }
        }
      }
    };
    walk("src");

    expect(offenders).toEqual([]);
  });
});
