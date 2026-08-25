import { describe, expect, it, vi } from "vitest";

/**
 * Scope guard for the Phase 0 foundation.
 *
 * Nothing in the console may persist operator state to the browser. If a future
 * change reintroduces it, this fails rather than silently shipping a
 * pseudo-authenticated session.
 */
describe("browser storage", () => {
  it("is never written by the store or the mock transport", async () => {
    const localSet = vi.spyOn(Storage.prototype, "setItem");

    const { makeStore, setDemoOperator } = await import("@/store");
    const store = makeStore();
    store.dispatch(setDemoOperator({ operatorId: "analyst.a1", role: "ANALYST" }));

    expect(localSet).not.toHaveBeenCalled();
    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);

    localSet.mockRestore();
  });

  it("has no source file referencing web storage APIs", async () => {
    const { readdirSync, readFileSync, statSync } = await import("node:fs");
    const { join } = await import("node:path");

    const offenders: string[] = [];
    const walk = (dir: string): void => {
      for (const entry of readdirSync(dir)) {
        const full = join(dir, entry);
        if (statSync(full).isDirectory()) {
          walk(full);
        } else if (/\.tsx?$/.test(entry)) {
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
