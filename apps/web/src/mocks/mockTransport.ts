/**
 * TEMPORARY resolver for the endpoints that have no server counterpart yet.
 *
 * Four screens' worth — the overview, reports, model and policy, and system
 * health — each of which needs a decision before it can have one. See
 * `docs/frontend/API_MIGRATION_AUDIT.md`; the endpoints that reach it are the
 * ones marked `transport: "mock"` in `src/api/sentinelApi.ts`, and this file
 * goes with the last of them.
 *
 * It answers in the same {@link SentinelError} shape the real transport does,
 * so no screen has to know which half of the migration it is looking at.
 */
import type { SentinelError, SentinelRequest } from "@/api/transport";
import * as mock from "./mockApi";
import { MockApiError } from "./mockApi";

const MOCK_LATENCY_MS = 260;

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function resolve(request: SentinelRequest): unknown {
  switch (request.url) {
    case "/overview":
      return mock.getOverview();
    case "/reports":
      return mock.getReports();
    case "/model-policy":
      return mock.getModelPolicy();
    case "/health":
      return mock.getSystemHealth();
    default:
      throw new MockApiError(404, `No mock resolver for ${request.method ?? "GET"} ${request.url}`);
  }
}

export async function mockTransport(
  request: SentinelRequest,
): Promise<{ data: unknown } | { error: SentinelError }> {
  await delay(MOCK_LATENCY_MS);
  try {
    return { data: resolve(request) };
  } catch (error) {
    if (error instanceof MockApiError) {
      return { error: { status: error.status, title: "Not available", detail: error.message } };
    }
    return {
      error: {
        status: 500,
        title: "Unexpected mock layer failure",
        detail: error instanceof Error ? error.message : "The fixture resolver threw.",
      },
    };
  }
}
