/**
 * SentinelFlow domain model.
 *
 * These types mirror the contracts of the external Spring Boot / FastAPI
 * `/api/v1` services. They describe SYNTHETIC data only.
 */

export const RISK_BANDS = ["LOW", "MEDIUM", "HIGH", "CRITICAL"] as const;
export type RiskBand = (typeof RISK_BANDS)[number];

export const ALERT_STATUSES = [
  "NEW",
  "IN_REVIEW",
  "ESCALATED",
  "CONFIRMED_SUSPICIOUS",
  "DISMISSED_FALSE_POSITIVE",
  "CLOSED",
] as const;
export type AlertStatus = (typeof ALERT_STATUSES)[number];

export const ROLES = ["ANALYST", "ADMINISTRATOR", "AUDITOR"] as const;
export type Role = (typeof ROLES)[number];

export const ALERT_PRIORITIES = ["P1", "P2", "P3", "P4"] as const;
export type AlertPriority = (typeof ALERT_PRIORITIES)[number];

export type TransactionStatus = "AUTHORIZED" | "DECLINED" | "PENDING" | "REVERSED";

/** Money is always a decimal string plus an explicit currency code. */
export interface Money {
  amount: string;
  currency: string;
}

export interface ReasonCode {
  code: string;
  label: string;
  /** Contribution to the final score, expressed in score points as a string. */
  contribution: string;
  source: "RULE" | "MODEL";
}

export interface RiskAssessment {
  assessmentId: string;
  ruleScore: number;
  modelScore: number;
  finalScore: number;
  riskBand: RiskBand;
  modelVersion: string;
  featureVersion: string;
  policyVersion: string;
  reasonCodes: ReasonCode[];
  scoringLatencyMs: number;
  assessedAt: string;
}

export interface Transaction {
  transactionId: string;
  accountId: string;
  merchantId: string;
  merchantCategory: string;
  channel: "CARD_PRESENT" | "CARD_NOT_PRESENT" | "ACH" | "WIRE" | "INSTANT_TRANSFER";
  money: Money;
  status: TransactionStatus;
  countryCode: string;
  deviceId: string;
  occurredAt: string;
  correlationId: string;
  assessment: RiskAssessment;
}

export interface AlertSummary {
  alertId: string;
  transactionId: string;
  accountId: string;
  priority: AlertPriority;
  status: AlertStatus;
  riskBand: RiskBand;
  finalScore: number;
  topReasonCode: string;
  assignee: string | null;
  createdAt: string;
  ageMinutes: number;
  money: Money;
}

export type TimelineEventKind = "CREATED" | "ASSIGNED" | "STATUS_CHANGE" | "NOTE" | "SYSTEM";

export interface TimelineEvent {
  eventId: string;
  kind: TimelineEventKind;
  actor: string;
  message: string;
  occurredAt: string;
}

export interface AuditEntry {
  entryId: string;
  action: string;
  actor: string;
  actorRole: Role;
  occurredAt: string;
  detail: string;
}

export interface AlertDetail extends AlertSummary {
  transaction: Transaction;
  timeline: TimelineEvent[];
  audit: AuditEntry[];
  notes: { noteId: string; author: string; body: string; createdAt: string }[];
}

export interface RelatedAccountActivity {
  transactionId: string;
  occurredAt: string;
  money: Money;
  merchantId: string;
  riskBand: RiskBand;
  status: TransactionStatus;
}

export interface TransactionDetail {
  transaction: Transaction;
  relatedActivity: RelatedAccountActivity[];
  linkedAlertId: string | null;
}

export interface Paginated<T> {
  items: T[];
  page: number;
  pageSize: number;
  totalItems: number;
  totalPages: number;
}

export interface ThroughputPoint {
  bucketStart: string;
  scored: number;
  alerted: number;
}

export interface RiskBandCount {
  riskBand: RiskBand;
  count: number;
}

export interface StatusCount {
  status: AlertStatus;
  count: number;
}

export interface LatencySummary {
  p50Ms: number;
  p95Ms: number;
  p99Ms: number;
  windowLabel: string;
}

export type HealthState = "OPERATIONAL" | "DEGRADED" | "OUTAGE" | "UNKNOWN";

export interface ComponentHealth {
  componentId: string;
  name: string;
  state: HealthState;
  detail: string;
  lastCheckedAt: string;
}

export interface PipelineHealth {
  consumerGroups: { groupId: string; topic: string; lagMessages: number }[];
  dlqDepth: number;
  dlqTopic: string;
}

export interface OverviewSnapshot {
  throughput: ThroughputPoint[];
  riskBands: RiskBandCount[];
  alertStatuses: StatusCount[];
  latency: LatencySummary;
  pipeline: PipelineHealth;
  recentAlerts: AlertSummary[];
  generatedAt: string;
}

export interface ReportsSnapshot {
  dailyAlertTrend: { date: string; alerts: number; escalated: number }[];
  riskBands: RiskBandCount[];
  feedback: { outcome: string; count: number }[];
  windowLabel: string;
}

export interface ModelPolicySnapshot {
  modelVersion: string;
  featureVersion: string;
  policyVersion: string;
  trainedAt: string;
  metrics: { key: string; label: string; value: string }[];
  thresholds: { riskBand: RiskBand; minFinalScore: number; action: string }[];
  limitations: string[];
}

export interface SystemHealthSnapshot {
  components: ComponentHealth[];
  pipeline: PipelineHealth;
  checkedAt: string;
}

/** UX-only permission hint. Real authorization lives in the backend. */
export function canMutate(role: Role): boolean {
  return role !== "AUDITOR";
}

export const ALLOWED_TRANSITIONS: Record<AlertStatus, AlertStatus[]> = {
  NEW: ["IN_REVIEW", "DISMISSED_FALSE_POSITIVE"],
  IN_REVIEW: ["ESCALATED", "CONFIRMED_SUSPICIOUS", "DISMISSED_FALSE_POSITIVE"],
  ESCALATED: ["CONFIRMED_SUSPICIOUS", "DISMISSED_FALSE_POSITIVE"],
  CONFIRMED_SUSPICIOUS: ["CLOSED"],
  DISMISSED_FALSE_POSITIVE: ["CLOSED"],
  CLOSED: [],
};
