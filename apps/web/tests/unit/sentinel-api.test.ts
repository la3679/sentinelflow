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

  it("issues no real network request while the mock layer is active", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    const store = makeStore();

    await store.dispatch(sentinelApi.endpoints.getOverview.initiate());
    await store.dispatch(
      sentinelApi.endpoints.listAlerts.initiate({
        page: 1,
        pageSize: 20,
        status: "ALL",
        riskBand: "ALL",
        search: "",
      }),
    );

    expect(fetchSpy).not.toHaveBeenCalled();
    fetchSpy.mockRestore();
  });

  it("returns a coherent overview snapshot", async () => {
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

  it("paginates alerts and reports a total that matches the filter", async () => {
    const store = makeStore();
    const args = {
      page: 1,
      pageSize: 5,
      status: "ALL" as const,
      riskBand: "ALL" as const,
      search: "",
    };
    const result = await store.dispatch(sentinelApi.endpoints.listAlerts.initiate(args));

    expect(result.isSuccess).toBe(true);
    const page = result.data!;
    expect(page.items.length).toBeLessThanOrEqual(5);
    expect(page.totalItems).toBeGreaterThanOrEqual(page.items.length);
    for (const alert of page.items) {
      expect(ALERT_STATUSES).toContain(alert.status);
    }
  });

  it("surfaces a 404 as a typed error rather than throwing", async () => {
    const store = makeStore();
    const result = await store.dispatch(
      sentinelApi.endpoints.getAlert.initiate("ALR-does-not-exist"),
    );

    expect(result.isError).toBe(true);
    expect(result.error).toMatchObject({ status: 404 });
  });

  it("is deterministic: two stores return identical alert pages", async () => {
    const args = {
      page: 1,
      pageSize: 10,
      status: "ALL" as const,
      riskBand: "ALL" as const,
      search: "",
    };
    const first = await makeStore().dispatch(sentinelApi.endpoints.listAlerts.initiate(args));
    const second = await makeStore().dispatch(sentinelApi.endpoints.listAlerts.initiate(args));

    expect(first.data).toEqual(second.data);
  });
});
