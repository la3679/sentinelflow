import { describe, expect, it, vi } from "vitest";

import { API_BASE_URL } from "@/api/config";
import { sentinelApi } from "@/api/sentinelApi";
import { makeStore } from "@/store";

describe("RTK Query data access", () => {
  it("points at the API's own origin, not at a path on the console's", () => {
    // ADR-0013: the two are separate origins. A relative base resolves against
    // the console's own static-file server, which answers a path it does not
    // serve with the application shell - a 200 the client then tries to parse
    // as JSON, which is a worse failure than a 404.
    expect(API_BASE_URL).toMatch(/^https?:\/\//);
    expect(API_BASE_URL.endsWith("/api/v1")).toBe(true);
  });

  it("sends the alert queue over HTTP, at the contract's path and parameters", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(
        JSON.stringify({
          content: [],
          page: { page: 0, size: 20, totalElements: 0, totalPages: 0 },
        }),
        {
          status: 200,
          headers: { "content-type": "application/json" },
        },
      ),
    );
    const store = makeStore();

    await store.dispatch(
      sentinelApi.endpoints.listAlerts.initiate({ page: 0, size: 20, status: "NEW" }),
    );

    const url = String((fetchSpy.mock.calls[0]?.[0] as Request).url);
    expect(url).toContain("/alerts");
    expect(url).toContain("page=0");
    expect(url).toContain("size=20");
    expect(url).toContain("status=NEW");
    fetchSpy.mockRestore();
  });

  it("leaves an unchosen filter off the query string rather than sending it empty", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(
        JSON.stringify({
          content: [],
          page: { page: 0, size: 20, totalElements: 0, totalPages: 0 },
        }),
        {
          status: 200,
          headers: { "content-type": "application/json" },
        },
      ),
    );
    const store = makeStore();

    await store.dispatch(sentinelApi.endpoints.listAlerts.initiate({ page: 0, size: 20 }));

    // The API has no "any status" value, so an unset filter is an absent
    // parameter. Sending an empty one would be a request the contract does not
    // describe, and the server would be right to refuse it.
    const url = String((fetchSpy.mock.calls[0]?.[0] as Request).url);
    expect(url).not.toContain("status=");
    expect(url).not.toContain("priority=");
    fetchSpy.mockRestore();
  });

  it("sends no client-supplied actor on a mutation", async () => {
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(
        new Response("{}", { status: 200, headers: { "content-type": "application/json" } }),
      );
    const store = makeStore();

    await store.dispatch(
      sentinelApi.endpoints.transitionAlert.initiate({
        alertId: "11111111-1111-4111-a111-111111111111",
        targetStatus: "IN_REVIEW",
        expectedVersion: 0,
      }),
    );

    // ADR-0012: the actor is the token's `sub`. A client that names its own
    // actor is a forgeable audit trail, so the field must not survive the
    // migration even as an ignored one.
    const body = await (fetchSpy.mock.calls[0]?.[0] as Request).json();
    expect(body).toEqual({ targetStatus: "IN_REVIEW", expectedVersion: 0 });
    fetchSpy.mockRestore();
  });

  it("sends the version a transition is being made against", async () => {
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(
        new Response("{}", { status: 200, headers: { "content-type": "application/json" } }),
      );
    const store = makeStore();

    await store.dispatch(
      sentinelApi.endpoints.transitionAlert.initiate({
        alertId: "11111111-1111-4111-a111-111111111111",
        targetStatus: "CLOSED",
        expectedVersion: 3,
      }),
    );

    // Optional optimistic concurrency is not optimistic concurrency: the API
    // refuses a request without it before reading anything.
    const body = await (fetchSpy.mock.calls[0]?.[0] as Request).json();
    expect(body).toMatchObject({ expectedVersion: 3 });
    fetchSpy.mockRestore();
  });

  it("asks for a report over an explicit half-open window", async () => {
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(
        new Response("{}", { status: 200, headers: { "content-type": "application/json" } }),
      );
    const store = makeStore();

    await store.dispatch(
      sentinelApi.endpoints.getAlertSummary.initiate({
        from: "2026-08-01T00:00:00.000Z",
        to: "2026-08-02T00:00:00.000Z",
      }),
    );

    // Both bounds are required by the contract. A report with an implicit
    // window is one whose figures cannot be reproduced.
    const url = String((fetchSpy.mock.calls[0]?.[0] as Request).url);
    expect(url).toContain("/reports/alert-summary");
    expect(url).toContain("from=2026-08-01");
    expect(url).toContain("to=2026-08-02");
    fetchSpy.mockRestore();
  });

  it("reads the export as a file rather than as JSON", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response("alertReference,status\nALT-0001,NEW\n", {
        status: 200,
        headers: { "content-type": "text/csv;charset=UTF-8" },
      }),
    );
    const store = makeStore();

    const result = await store.dispatch(
      sentinelApi.endpoints.exportAlerts.initiate({
        from: "2026-08-01T00:00:00.000Z",
        to: "2026-08-02T00:00:00.000Z",
      }),
    );

    // Through the transport rather than a bare fetch, so it carries the bearer
    // token and a refusal is the one error shape every screen handles - but the
    // body is a file, and parsing it as JSON would fail on the first row.
    //
    // Asserted on the content rather than with `instanceof Blob`: the Blob the
    // fetch polyfill produces is not the one this realm declares, and a test
    // that failed on that would be checking the test environment.
    const body = "data" in result ? ((await (result.data as Blob).text()) ?? "") : "";
    expect(body).toContain("alertReference,status");
    fetchSpy.mockRestore();
  });

  it("surfaces a problem document as a typed error rather than throwing", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(
        JSON.stringify({
          type: "https://sentinelflow.example/problems/alert-not-found",
          title: "Alert not found",
          status: 404,
          detail: "No alert has that identifier.",
        }),
        { status: 404, headers: { "content-type": "application/problem+json" } },
      ),
    );
    const store = makeStore();

    const result = await store.dispatch(
      sentinelApi.endpoints.getAlert.initiate("11111111-1111-4111-a111-111111111111"),
    );

    expect(result.isError).toBe(true);
    expect(result.error).toMatchObject({ status: 404, detail: "No alert has that identifier." });
    fetchSpy.mockRestore();
  });

  it("composes the overview from endpoints that exist, rather than one that does not", async () => {
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(
        new Response("{}", { status: 200, headers: { "content-type": "application/json" } }),
      );
    const store = makeStore();

    await store.dispatch(
      sentinelApi.endpoints.getAlertSummary.initiate({
        from: "2026-08-01T00:00:00.000Z",
        to: "2026-08-02T00:00:00.000Z",
      }),
    );
    await store.dispatch(sentinelApi.endpoints.listAlerts.initiate({ page: 0, size: 8 }));

    // ADR-0014 SS3: an aggregate endpoint would be a second implementation of
    // risk-band counting beside the report that already does it, and the two
    // would disagree the first time one changed.
    const urls = fetchSpy.mock.calls.map((call) => String((call[0] as Request).url));
    expect(urls.some((url) => url.includes("/reports/alert-summary"))).toBe(true);
    expect(urls.some((url) => url.includes("/alerts"))).toBe(true);
    expect(urls.some((url) => url.includes("/overview"))).toBe(false);
    fetchSpy.mockRestore();
  });
});
