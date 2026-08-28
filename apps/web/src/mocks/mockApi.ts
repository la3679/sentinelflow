/**
 * TEMPORARY resolvers for the four endpoints this console invents.
 *
 * The overview, the reports screen, the model and policy screen, and system
 * health. Each needs a decision before it can be real —
 * `docs/frontend/API_MIGRATION_AUDIT.md` sets out what each one wants and what
 * already exists that could answer it.
 *
 * The alert and transaction resolvers that used to live here are gone: those
 * screens read the API.
 */
import {
  ALERT_STATUSES,
  RISK_BANDS,
  type Alert,
  type ModelPolicySnapshot,
  type OverviewSnapshot,
  type ReportsSnapshot,
  type RiskBandCount,
  type StatusCount,
  type SystemHealthSnapshot,
} from "@/domain/types";
import {
  ALERTS,
  HEALTH_COMPONENTS,
  MOCK_EPOCH,
  MODEL_POLICY,
  PIPELINE_HEALTH,
  REPORTS,
  SCORED_BAND_COUNTS,
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

function scoredBands(): RiskBandCount[] {
  return RISK_BANDS.map((riskBand) => ({ riskBand, count: SCORED_BAND_COUNTS[riskBand] }));
}

function alertBands(alerts: readonly Alert[]): RiskBandCount[] {
  return RISK_BANDS.map((riskBand) => ({
    riskBand,
    count: alerts.filter((a) => a.riskBand === riskBand).length,
  }));
}

function countStatuses(alerts: readonly Alert[]): StatusCount[] {
  return ALERT_STATUSES.map((status) => ({
    status,
    count: alerts.filter((a) => a.status === status).length,
  }));
}

export function getOverview(): OverviewSnapshot {
  return {
    throughput: throughputSeries(),
    riskBands: scoredBands(),
    alertStatuses: countStatuses(ALERTS),
    latency: { p50Ms: 42, p95Ms: 118, p99Ms: 187, windowLabel: "Last 60 minutes" },
    pipeline: PIPELINE_HEALTH,
    recentAlerts: [...ALERTS]
      .sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))
      .slice(0, 8),
    generatedAt: new Date(MOCK_EPOCH).toISOString(),
  };
}

export function getReports(): ReportsSnapshot {
  return { ...REPORTS, riskBands: alertBands(ALERTS) };
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
