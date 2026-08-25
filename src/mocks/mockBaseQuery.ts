/**
 * TEMPORARY transport shim.
 *
 * RTK Query endpoints declare real `/api/v1` request descriptors. Today this
 * shim resolves them from the deterministic mock fixtures; swapping it for
 * `fetchBaseQuery({ baseUrl: API_BASE_URL })` is the only change needed once the
 * external backend is available.
 */
import type { BaseQueryFn } from "@reduxjs/toolkit/query";

import { API_BASE_URL } from "@/api/config";
import type { AlertStatus, RiskBand, Transaction } from "@/domain/types";
import * as mock from "./mockApi";
import { MockApiError } from "./mockApi";

export interface ApiRequest {
  url: string;
  method?: "GET" | "POST" | "PATCH";
  params?: Record<string, string | number | undefined>;
  body?: Record<string, string>;
}

export interface ApiError {
  status: number;
  message: string;
}

const MOCK_LATENCY_MS = 260;

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function num(value: string | number | undefined, fallback: number): number {
  const parsed = typeof value === "number" ? value : Number.parseInt(value ?? "", 10);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function str(value: string | number | undefined): string | undefined {
  return value === undefined ? undefined : String(value);
}

function resolve(request: ApiRequest): unknown {
  const path = request.url.startsWith(API_BASE_URL)
    ? request.url.slice(API_BASE_URL.length)
    : request.url;
  const params: Record<string, string | number | undefined> = request.params ?? {};
  const body = request.body ?? {};

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

export const mockBaseQuery: BaseQueryFn<ApiRequest, unknown, ApiError> = async (request) => {
  await delay(MOCK_LATENCY_MS);
  try {
    return { data: resolve(request) };
  } catch (error) {
    if (error instanceof MockApiError) {
      return { error: { status: error.status, message: error.message } };
    }
    return {
      error: {
        status: 500,
        message: error instanceof Error ? error.message : "Unexpected mock layer failure.",
      },
    };
  }
};
