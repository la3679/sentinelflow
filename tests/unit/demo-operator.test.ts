import { describe, expect, it } from "vitest";

import {
  DEFAULT_DEMO_OPERATOR,
  demoOperatorSlice,
  resetDemoOperator,
  setDemoOperator,
  setRole,
} from "@/store/demoOperatorSlice";

const reducer = demoOperatorSlice.reducer;

describe("demoOperator slice", () => {
  it("starts as the default analyst operator", () => {
    expect(reducer(undefined, { type: "@@INIT" })).toEqual(DEFAULT_DEMO_OPERATOR);
  });

  it("sets the operator identity and role together", () => {
    const next = reducer(undefined, setDemoOperator({ operatorId: "auditor.z9", role: "AUDITOR" }));
    expect(next).toEqual({ operatorId: "auditor.z9", role: "AUDITOR" });
  });

  it("changes role without disturbing the operator id", () => {
    const signedIn = reducer(undefined, setDemoOperator({ operatorId: "a.1", role: "ANALYST" }));
    expect(reducer(signedIn, setRole("ADMINISTRATOR"))).toEqual({
      operatorId: "a.1",
      role: "ADMINISTRATOR",
    });
  });

  it("resets back to the default operator", () => {
    const changed = reducer(undefined, setDemoOperator({ operatorId: "x", role: "AUDITOR" }));
    expect(reducer(changed, resetDemoOperator())).toEqual(DEFAULT_DEMO_OPERATOR);
  });

  // Scope guard. This phase must not build an authentication shape.
  it("models no authentication state", () => {
    const state = reducer(undefined, setDemoOperator({ operatorId: "a.1", role: "ANALYST" }));
    expect(Object.keys(state).sort()).toEqual(["operatorId", "role"]);
    for (const forbidden of ["signedIn", "hydrated", "token", "accessToken", "password"]) {
      expect(state).not.toHaveProperty(forbidden);
    }
  });
});
