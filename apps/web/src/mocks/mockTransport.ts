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
import type { AlertStatus, RiskBand, Transaction } from "@/domain/types";
import * as mock from "./mockApi";
import { MockApiError } from "./mockApi";

const MOCK_LATENCY_MS = 260;

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function num(value: string | number | boolean | undefined, fallback: number): number {
  const parsed = typeof value === "number" ? value : Number.parseInt(String(value ?? ""), 10);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function str(value: string | number | boolean | undefined): string | undefined {
  return value === undefined ? undefined : String(value);
}

function fields(body: unknown): Record<string, string> {
  return typeof body === "object" && body !== null ? (body as Record<string, string>) : {};
}

function resolve(request: SentinelRequest): unknown {
  const path = request.url;
  const params: Record<string, string | number | boolean | undefined> = request.params ?? {};
  const body = fields(request.body);

  if (path === "/overview") return mock.getOverview();
  if (path === "/reports") return mock.getReports();
  if (path === "/model-policy") return mock.getModelPolicy();
  if (path === "/health") return mock.getSystemHealth();

  if (path === "/transactions") {
    return mock.listTransactions({
      page: num(params["page"], 1),
      pageSize: num(params["pageSize"], 25),
      riskBand: str(params["riskBand"]) as RiskBand | "ALL" | undefined,
      status: str(params["status"]) as Transaction["status"] | "ALL" | undefined,
      search: str(params["search"]),
    });
  }

  if (path === "/alerts") {
    return mock.listAlerts({
      page: num(params["page"], 1),
      pageSize: num(params["pageSize"], 20),
      status: str(params["status"]) as AlertStatus | "ALL" | undefined,
      riskBand: str(params["riskBand"]) as RiskBand | "ALL" | undefined,
      search: str(params["search"]),
    });
  }

  const transactionMatch = /^\/transactions\/([^/]+)$/.exec(path);
  if (transactionMatch)
    return mock.getTransaction(decodeURIComponent(transactionMatch[1] as string));

  const assignMatch = /^\/alerts\/([^/]+)\/assignee$/.exec(path);
  if (assignMatch) {
    return mock.assignAlert(
      decodeURIComponent(assignMatch[1] as string),
      body["assignee"] ?? "unassigned",
      body["actor"] ?? "analyst.a1",
    );
  }

  const statusMatch = /^\/alerts\/([^/]+)\/status$/.exec(path);
  if (statusMatch) {
    return mock.transitionAlert(
      decodeURIComponent(statusMatch[1] as string),
      body["status"] as AlertStatus,
      body["actor"] ?? "analyst.a1",
    );
  }

  const notesMatch = /^\/alerts\/([^/]+)\/notes$/.exec(path);
  if (notesMatch) {
    return mock.addNote(
      decodeURIComponent(notesMatch[1] as string),
      body["body"] ?? "",
      body["actor"] ?? "analyst.a1",
    );
  }

  const alertMatch = /^\/alerts\/([^/]+)$/.exec(path);
  if (alertMatch) return mock.getAlert(decodeURIComponent(alertMatch[1] as string));

  throw new MockApiError(404, `No mock resolver for ${request.method ?? "GET"} ${path}`);
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
