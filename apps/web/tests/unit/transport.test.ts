import { afterEach, describe, expect, it, vi } from "vitest";

import type { BaseQueryApi } from "@reduxjs/toolkit/query";

import { sentinelApi } from "@/api/sentinelApi";
import { API_BASE_URL } from "@/api/config";
import { sentinelBaseQuery } from "@/api/transport";
import { makeStore, signedIn } from "@/store";

type Store = ReturnType<typeof makeStore>;

/**
 * The context RTK Query hands a base query.
 *
 * Built by hand so the transport can be exercised at a path no endpoint has
 * been migrated to yet. The alternative — waiting for one — would leave the
 * session-ending 401 untested through the whole migration, which is the part
 * of it most likely to be wrong.
 */
function baseQueryApi(store: Store): BaseQueryApi {
  return {
    signal: new AbortController().signal,
    abort: () => {},
    dispatch: store.dispatch,
    getState: store.getState,
    extra: undefined,
    endpoint: "listAlerts",
    type: "query",
    forced: false,
  };
}

interface FetchCall {
  url: string;
  method: string;
  headers: Headers;
  body: string | null;
}

/** Replaces `fetch` and records what the transport actually put on the wire. */
function captureFetch(respond: (call: FetchCall) => Response): { calls: FetchCall[] } {
  const calls: FetchCall[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    const request = new Request(input as RequestInfo, init);
    const call: FetchCall = {
      url: request.url,
      method: request.method,
      headers: request.headers,
      // From the Request rather than from `init`: fetchBaseQuery builds one and
      // calls fetch with it alone, so `init` is empty by the time it gets here.
      body: request.body === null ? null : await request.clone().text(),
    };
    calls.push(call);
    return respond(call);
  });
  return { calls };
}

function json(body: unknown, status = 200, contentType = "application/json"): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": contentType },
  });
}

const A_TOKEN = {
  username: "analyst.one",
  token: "a.jwt.value",
  tokenType: "Bearer",
  expiresAt: "2026-08-28T20:00:00Z",
  roles: ["ANALYST"] as const,
};

afterEach(() => {
  vi.restoreAllMocks();
});

describe("the real transport", () => {
  it("sends the login request to the API's own origin, as JSON, with no token", async () => {
    const { calls } = captureFetch(() =>
      json({
        token: "a.jwt.value",
        tokenType: "Bearer",
        expiresAt: "2026-08-28T20:00:00Z",
        roles: ["ANALYST"],
      }),
    );
    const store = makeStore();

    const result = await store.dispatch(
      sentinelApi.endpoints.login.initiate({ username: "analyst.one", password: "secret" }),
    );

    expect(calls).toHaveLength(1);
    expect(calls[0]!.url).toBe(`${API_BASE_URL}/auth/login`);
    expect(calls[0]!.method).toBe("POST");
    expect(calls[0]!.headers.get("Authorization")).toBeNull();
    expect(result.data?.roles).toEqual(["ANALYST"]);
  });

  it("puts the bearer token on every later request, in the type the API sent", async () => {
    const { calls } = captureFetch(() => json({ items: [], page: 1, pageSize: 20, totalItems: 0 }));
    const store = makeStore();
    store.dispatch(signedIn({ ...A_TOKEN, roles: ["ANALYST"] }));

    // Any real endpoint would do; login is the only one migrated so far, so the
    // header is asserted through the same call with a session in place.
    await store.dispatch(
      sentinelApi.endpoints.login.initiate({ username: "analyst.one", password: "secret" }),
    );

    expect(calls[0]!.headers.get("Authorization")).toBe("Bearer a.jwt.value");
  });

  it("never puts the password anywhere but the request body", async () => {
    const { calls } = captureFetch(() =>
      json({ token: "t", tokenType: "Bearer", expiresAt: "2026-08-28T20:00:00Z", roles: [] }),
    );
    const store = makeStore();

    await store.dispatch(
      sentinelApi.endpoints.login.initiate({ username: "analyst.one", password: "hunter2" }),
    );

    expect(calls[0]!.url).not.toContain("hunter2");
    expect([...calls[0]!.headers.values()].join(" ")).not.toContain("hunter2");
    expect(calls[0]!.body).toContain("hunter2");
  });
});

describe("a refusal the console has to show somebody", () => {
  it("reads RFC 9457, keeping the detail and the correlation identifier", async () => {
    captureFetch(() =>
      json(
        {
          type: "about:blank",
          title: "Unauthorized",
          status: 401,
          detail: "The username and password were not accepted.",
          correlationId: "8f14e45f-ceea-467a-9e5f-2b4c1e8a0000",
        },
        401,
        "application/problem+json",
      ),
    );
    const store = makeStore();

    const result = await store.dispatch(
      sentinelApi.endpoints.login.initiate({ username: "analyst.one", password: "wrong" }),
    );

    expect(result.error).toMatchObject({
      status: 401,
      title: "Unauthorized",
      detail: "The username and password were not accepted.",
      correlationId: "8f14e45f-ceea-467a-9e5f-2b4c1e8a0000",
    });
  });

  it("keeps the properties a 409 answers with, because they are what makes it answerable", async () => {
    captureFetch(() =>
      json(
        {
          type: "about:blank",
          title: "Conflict",
          status: 409,
          detail: "The alert changed since it was read.",
          currentVersion: 4,
          legalTargets: ["IN_REVIEW", "CLOSED"],
        },
        409,
        "application/problem+json",
      ),
    );
    const store = makeStore();

    const result = await store.dispatch(
      sentinelApi.endpoints.login.initiate({ username: "analyst.one", password: "x" }),
    );

    expect(result.error).toMatchObject({
      status: 409,
      extensions: { currentVersion: 4, legalTargets: ["IN_REVIEW", "CLOSED"] },
    });
  });

  it("says the API could not be reached when nothing answers at all", async () => {
    vi.spyOn(globalThis, "fetch").mockRejectedValue(new TypeError("Failed to fetch"));
    const store = makeStore();

    const result = await store.dispatch(
      sentinelApi.endpoints.login.initiate({ username: "analyst.one", password: "x" }),
    );

    // A browser will not tell script whether this was a dead API or a CORS
    // refusal, so the message names both rather than guessing.
    expect(result.error).toMatchObject({ status: 0, title: "The API could not be reached." });
    expect((result.error as { detail: string }).detail).toContain("allowed origins");
  });
});

describe("the 401 that ends a session", () => {
  it("ends the session when a request that carried a token is refused", async () => {
    // There is no refresh token by design (ADR-0012 section 3), so the expiry
    // arrives as a 401 on whatever the operator did next.
    captureFetch(() => json({ title: "Unauthorized", status: 401 }, 401));
    const store = makeStore();
    store.dispatch(signedIn({ ...A_TOKEN, roles: ["ANALYST"] }));

    await sentinelBaseQuery({ url: "/alerts" }, baseQueryApi(store), {});

    expect(store.getState().session.status).toBe("expired");
    expect(store.getState().session.token).toBeNull();
  });

  it("leaves an anonymous browser anonymous, rather than telling it a session ended", async () => {
    captureFetch(() => json({ title: "Unauthorized", status: 401 }, 401));
    const store = makeStore();

    await sentinelBaseQuery({ url: "/alerts" }, baseQueryApi(store), {});

    expect(store.getState().session.status).toBe("anonymous");
  });

  it("does not end a session that a wrong password never started", async () => {
    captureFetch(() => json({ title: "Unauthorized", status: 401 }, 401));
    const store = makeStore();

    await store.dispatch(
      sentinelApi.endpoints.login.initiate({ username: "analyst.one", password: "wrong" }),
    );

    expect(store.getState().session.status).toBe("anonymous");
  });
});

describe("every endpoint goes over HTTP", () => {
  it("leaves nothing resolving from an in-memory fixture", async () => {
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(
        new Response("{}", { status: 200, headers: { "content-type": "application/json" } }),
      );
    const store = makeStore();

    // The screen that was the last to have no server counterpart. When this
    // made no request, `src/mocks/` existed; the assertion is inverted rather
    // than deleted so the deletion is what a reader sees.
    await store.dispatch(sentinelApi.endpoints.getSystemHealth.initiate());

    expect(fetchSpy).toHaveBeenCalled();
    fetchSpy.mockRestore();
  });
});
