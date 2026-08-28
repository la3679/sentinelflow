import { describe, expect, it, vi } from "vitest";

import { API_BASE_URL, USING_MOCK_DATA } from "@/api/config";
import { sentinelApi } from "@/api/sentinelApi";
import { makeStore } from "@/store";
import { ALERT_STATUSES, RISK_BANDS } from "@/domain/types";

describe("RTK Query data access", () => {
  it("points at the API's own origin, not at a path on the console's", () => {
    // ADR-0013: the two are separate origins. A relative base resolves against
    // the console's own static-file server, which answers a path it does not
    // serve with the application shell - a 200 the client then tries to parse
    // as JSON, which is a worse failure than a 404.
    expect(API_BASE_URL).toMatch(/^https?:\/\//);
    expect(API_BASE_URL.endsWith("/api/v1")).toBe(true);
    expect(USING_MOCK_DATA).toBe(true);
  });

  it("issues no network request for an endpoint that is still a fixture", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    const store = makeStore();

    await store.dispatch(sentinelApi.endpoints.getOverview.initiate());

    expect(fetchSpy).not.toHaveBeenCalled();
    fetchSpy.mockRestore();
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

  it("returns a coherent overview snapshot from the fixtures that still back it", async () => {
    const store = makeStore();
    const result = await store.dispatch(sentinelApi.endpoints.getOverview.initiate());

    expect(result.isSuccess).toBe(true);
    const data = result.data!;
    expect(data.riskBands.length).toBeGreaterThan(0);
    for (const slice of data.riskBands) {
      expect(RISK_BANDS).toContain(slice.riskBand);
    }
    expect(data.throughput.length).toBeGreaterThan(0);
    expect(data.recentAlerts.length).toBeGreaterThan(0);
    for (const alert of data.recentAlerts) {
      expect(ALERT_STATUSES).toContain(alert.status);
    }
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

  it("is deterministic: two stores return the same overview", async () => {
    const first = await makeStore().dispatch(sentinelApi.endpoints.getOverview.initiate());
    const second = await makeStore().dispatch(sentinelApi.endpoints.getOverview.initiate());

    expect(first.data).toEqual(second.data);
  });
});
