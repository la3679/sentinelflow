/**
 * TEMPORARY mock resolver layer.
 *
 * Resolves the same URL shapes the real `/api/v1` backend will serve, so the
 * RTK Query endpoint definitions stay unchanged when this file is deleted.
 */
import {
  ALERT_STATUSES,
  type AlertDetail,
  type AlertStatus,
  type AlertSummary,
  type OverviewSnapshot,
  type Paginated,
  type ReportsSnapshot,
  type ModelPolicySnapshot,
  type RiskBand,
  type RiskBandCount,
  type StatusCount,
  type SystemHealthSnapshot,
  type Transaction,
  type TransactionDetail,
  RISK_BANDS,
} from "@/domain/types";
import {
  ALERTS,
  HEALTH_COMPONENTS,
  MOCK_EPOCH,
  MODEL_POLICY,
  PIPELINE_HEALTH,
  REPORTS,
  TRANSACTIONS,
  alertForTransaction,
  buildAlertDetail,
  findAlertSummary,
  findTransaction,
  relatedActivity,
  throughputSeries,
} from "./fixtures";

export class MockApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = "MockApiError";
  }
}

/** In-memory mutation overlay so mock mutations behave consistently in-session. */
const alertOverrides = new Map<string, Partial<AlertSummary>>();
const extraNotes = new Map<string, AlertDetail["notes"]>();
const extraTimeline = new Map<string, AlertDetail["timeline"]>();

function withOverrides(summary: AlertSummary): AlertSummary {
  return { ...summary, ...(alertOverrides.get(summary.alertId) ?? {}) };
}

function countBands(bands: RiskBand[]): RiskBandCount[] {
  return RISK_BANDS.map((riskBand) => ({
    riskBand,
    count: bands.filter((b) => b === riskBand).length,
  }));
}

function countStatuses(items: AlertSummary[]): StatusCount[] {
  return ALERT_STATUSES.map((status) => ({
    status,
    count: items.filter((a) => a.status === status).length,
  }));
}

export interface AlertQuery {
  page?: number | undefined;
  pageSize?: number | undefined;
  status?: AlertStatus | "ALL" | undefined;
  riskBand?: RiskBand | "ALL" | undefined;
  search?: string | undefined;
}

export function listAlerts(query: AlertQuery): Paginated<AlertSummary> {
  const page = Math.max(1, query.page ?? 1);
  const pageSize = query.pageSize ?? 20;
  const search = (query.search ?? "").trim().toUpperCase();

  const filtered = ALERTS.map(withOverrides)
    .filter((a) => (query.status && query.status !== "ALL" ? a.status === query.status : true))
    .filter((a) =>
      query.riskBand && query.riskBand !== "ALL" ? a.riskBand === query.riskBand : true,
    )
    .filter((a) =>
      search
        ? a.alertId.includes(search) ||
          a.accountId.includes(search) ||
          a.transactionId.includes(search)
        : true,
    )
    .sort((a, b) => a.priority.localeCompare(b.priority) || b.finalScore - a.finalScore);

  const totalItems = filtered.length;
  const totalPages = Math.max(1, Math.ceil(totalItems / pageSize));
  const safePage = Math.min(page, totalPages);
  return {
    items: filtered.slice((safePage - 1) * pageSize, safePage * pageSize),
    page: safePage,
    pageSize,
    totalItems,
    totalPages,
  };
}

export function getAlert(alertId: string): AlertDetail {
  const summary = findAlertSummary(alertId);
  if (!summary) throw new MockApiError(404, `Alert ${alertId} was not found.`);
  const detail = buildAlertDetail(withOverrides(summary));
  return {
    ...detail,
    notes: [...detail.notes, ...(extraNotes.get(alertId) ?? [])],
    timeline: [...detail.timeline, ...(extraTimeline.get(alertId) ?? [])],
  };
}

export function assignAlert(alertId: string, assignee: string, actor: string): AlertDetail {
  const summary = findAlertSummary(alertId);
  if (!summary) throw new MockApiError(404, `Alert ${alertId} was not found.`);
  alertOverrides.set(alertId, { ...alertOverrides.get(alertId), assignee });
  pushTimeline(alertId, "ASSIGNED", actor, `Assigned to ${assignee}.`);
  return getAlert(alertId);
}

export function transitionAlert(alertId: string, status: AlertStatus, actor: string): AlertDetail {
  const summary = findAlertSummary(alertId);
  if (!summary) throw new MockApiError(404, `Alert ${alertId} was not found.`);
  alertOverrides.set(alertId, { ...alertOverrides.get(alertId), status });
  pushTimeline(alertId, "STATUS_CHANGE", actor, `Status set to ${status}.`);
  return getAlert(alertId);
}

export function addNote(alertId: string, body: string, actor: string): AlertDetail {
  const summary = findAlertSummary(alertId);
  if (!summary) throw new MockApiError(404, `Alert ${alertId} was not found.`);
  const notes = extraNotes.get(alertId) ?? [];
  extraNotes.set(alertId, [
    ...notes,
    {
      noteId: `${alertId}-N${notes.length + 100}`,
      author: actor,
      body,
      createdAt: new Date().toISOString(),
    },
  ]);
  pushTimeline(alertId, "NOTE", actor, "Investigation note added.");
  return getAlert(alertId);
}

function pushTimeline(
  alertId: string,
  kind: AlertDetail["timeline"][number]["kind"],
  actor: string,
  message: string,
): void {
  const events = extraTimeline.get(alertId) ?? [];
  extraTimeline.set(alertId, [
    ...events,
    {
      eventId: `${alertId}-X${events.length + 1}`,
      kind,
      actor,
      message,
      occurredAt: new Date().toISOString(),
    },
  ]);
}

export interface TransactionQuery {
  page?: number | undefined;
  pageSize?: number | undefined;
  riskBand?: RiskBand | "ALL" | undefined;
  status?: Transaction["status"] | "ALL" | undefined;
  search?: string | undefined;
}

export function listTransactions(query: TransactionQuery): Paginated<Transaction> {
  const search = (query.search ?? "").trim().toUpperCase();
  const filtered = TRANSACTIONS.filter((t) =>
    query.riskBand && query.riskBand !== "ALL" ? t.assessment.riskBand === query.riskBand : true,
  )
    .filter((t) => (query.status && query.status !== "ALL" ? t.status === query.status : true))
    .filter((t) =>
      search
        ? t.transactionId.includes(search) ||
          t.accountId.includes(search) ||
          t.merchantId.includes(search)
        : true,
    );

  const pageSize = query.pageSize ?? 25;
  const page = Math.max(1, query.page ?? 1);
  const totalItems = filtered.length;
  const totalPages = Math.max(1, Math.ceil(totalItems / pageSize));
  const safePage = Math.min(page, totalPages);
  return {
    items: filtered.slice((safePage - 1) * pageSize, safePage * pageSize),
    page: safePage,
    pageSize,
    totalItems,
    totalPages,
  };
}

export function getTransaction(transactionId: string): TransactionDetail {
  const transaction = findTransaction(transactionId);
  if (!transaction) throw new MockApiError(404, `Transaction ${transactionId} was not found.`);
  return {
    transaction,
    relatedActivity: relatedActivity(transaction.accountId, transaction.transactionId),
    linkedAlertId: alertForTransaction(transactionId)?.alertId ?? null,
  };
}

export function getOverview(): OverviewSnapshot {
  const alerts = ALERTS.map(withOverrides);
  return {
    throughput: throughputSeries(),
    riskBands: countBands(TRANSACTIONS.slice(0, 240).map((t) => t.assessment.riskBand)),
    alertStatuses: countStatuses(alerts),
    latency: { p50Ms: 42, p95Ms: 118, p99Ms: 187, windowLabel: "Last 60 minutes" },
    pipeline: PIPELINE_HEALTH,
    recentAlerts: [...alerts].sort((a, b) => a.ageMinutes - b.ageMinutes).slice(0, 8),
    generatedAt: new Date(MOCK_EPOCH).toISOString(),
  };
}

export function getReports(): ReportsSnapshot {
  return {
    ...REPORTS,
    riskBands: countBands(ALERTS.map(withOverrides).map((a) => a.riskBand)),
  };
}

export function getModelPolicy(): ModelPolicySnapshot {
  return MODEL_POLICY;
}

export function getSystemHealth(): SystemHealthSnapshot {
  return {
    components: HEALTH_COMPONENTS,
    pipeline: PIPELINE_HEALTH,
    checkedAt: new Date(MOCK_EPOCH).toISOString(),
  };
}
