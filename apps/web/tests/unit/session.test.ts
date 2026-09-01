import { describe, expect, it } from "vitest";

import {
  knownRoles,
  principalRole,
  sessionCanMutate,
  sessionExpired,
  signedIn,
  signedOut,
} from "@/store";
import { sessionSlice, type SignedInPayload } from "@/store/sessionSlice";

const reducer = sessionSlice.reducer;

const A_SESSION: SignedInPayload = {
  username: "analyst.one",
  token: "a.jwt.value",
  tokenType: "Bearer",
  expiresAt: "2026-08-28T20:00:00Z",
  operatorId: "11111111-1111-4111-a111-111111111111",
  displayName: "A. Analyst",
  roles: ["ANALYST"],
};

describe("the operator session", () => {
  it("starts anonymous, holding nothing", () => {
    const state = reducer(undefined, { type: "@@INIT" });

    expect(state.status).toBe("anonymous");
    expect(state.token).toBeNull();
    expect(state.roles).toEqual([]);
  });

  it("records the token, its type, its expiry and the roles that came with it", () => {
    const state = reducer(undefined, signedIn(A_SESSION));

    expect(state.status).toBe("authenticated");
    expect(state.token).toBe("a.jwt.value");
    expect(state.tokenType).toBe("Bearer");
    expect(state.expiresAt).toBe("2026-08-28T20:00:00Z");
    expect(state.roles).toEqual(["ANALYST"]);
  });

  it("never keeps the password: there is nowhere in the state to put one", () => {
    const state = reducer(undefined, signedIn(A_SESSION));

    // An allow-list rather than a "does not contain password" check, so a field
    // added to the session has to be added here deliberately. operatorId and
    // displayName arrived with ADR-0019: both come from the login response, and
    // neither is a credential - the operator's own identifier is already the
    // token's subject, and the name is what the API publishes for it.
    expect(Object.keys(state).sort()).toEqual([
      "displayName",
      "expiresAt",
      "operatorId",
      "roles",
      "status",
      "token",
      "tokenType",
      "username",
    ]);
  });

  it("drops the credential when the operator signs out", () => {
    const state = reducer(reducer(undefined, signedIn(A_SESSION)), signedOut());

    expect(state.status).toBe("anonymous");
    expect(state.token).toBeNull();
  });

  it("distinguishes an expired session from one that never existed", () => {
    // They need different sentences on the sign-in screen: one of them lost
    // work mid-review, and telling that operator nothing happened is wrong.
    const expired = reducer(reducer(undefined, signedIn(A_SESSION)), sessionExpired());

    expect(expired.status).toBe("expired");
    expect(expired.token).toBeNull();
    expect(reducer(undefined, { type: "@@INIT" }).status).toBe("anonymous");
  });
});

describe("roles from the login response", () => {
  it("keeps the three the contract names", () => {
    expect(knownRoles(["ANALYST", "ADMINISTRATOR", "AUDITOR"])).toEqual([
      "ANALYST",
      "ADMINISTRATOR",
      "AUDITOR",
    ]);
  });

  it("drops anything it cannot render, rather than storing it", () => {
    // A role this console has never heard of means it is older than the API it
    // is talking to. Dropping it offers fewer controls, which is the safe
    // direction; keeping it would put a value in the store that ROLE_LABELS
    // cannot render.
    expect(knownRoles(["ANALYST", "SYSTEM", "SUPERUSER", 7, null])).toEqual(["ANALYST"]);
    expect(knownRoles(undefined)).toEqual([]);
  });
});

describe("which role the interface reads", () => {
  it("takes the most privileged, the same way the API attributes an action", () => {
    // AuthenticatedOperator records an administrator who is also an analyst as
    // an administrator. Two different answers would mean offering a control
    // under one capacity and auditing it under another.
    expect(principalRole(["ANALYST", "ADMINISTRATOR"])).toBe("ADMINISTRATOR");
    expect(principalRole(["AUDITOR", "ANALYST"])).toBe("ANALYST");
    expect(principalRole([])).toBeNull();
  });

  it("offers case actions to the roles that can take them, and to no one else", () => {
    expect(sessionCanMutate(["ANALYST"])).toBe(true);
    expect(sessionCanMutate(["ADMINISTRATOR"])).toBe(true);
    expect(sessionCanMutate(["AUDITOR"])).toBe(false);
    expect(sessionCanMutate([])).toBe(false);
  });
});
