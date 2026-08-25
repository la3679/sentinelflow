/**
 * TEMPORARY deterministic mock fixture layer.
 *
 * Every value here is fictional synthetic data generated from a fixed seed so
 * the console renders identically on every load with no backend. This module
 * will be deleted once the real `/api/v1` client is wired up.
 */
import type {
  AlertDetail,
  AlertPriority,
  AlertStatus,
  AlertSummary,
  AuditEntry,
  ComponentHealth,
  ModelPolicySnapshot,
  PipelineHealth,
  ReasonCode,
  RelatedAccountActivity,
  ReportsSnapshot,
  RiskAssessment,
  RiskBand,
  ThroughputPoint,
  TimelineEvent,
  Transaction,
  TransactionStatus,
} from "@/domain/types";
import { ALERT_STATUSES } from "@/domain/types";

export const MOCK_EPOCH = Date.parse("2026-08-25T12:00:00.000Z");

const MODEL_VERSION = "risk-model-2026.07.3";
const FEATURE_VERSION = "features-v14";
const POLICY_VERSION = "policy-2026.08.1";

function mulberry32(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function pick<T>(rng: () => number, values: readonly T[]): T {
  return values[Math.floor(rng() * values.length)] as T;
}

function pad(value: number, width: number): string {
  return String(value).padStart(width, "0");
}

const CHANNELS = ["CARD_PRESENT", "CARD_NOT_PRESENT", "ACH", "WIRE", "INSTANT_TRANSFER"] as const;

const CATEGORIES = [
  "ELECTRONICS",
  "GROCERY",
  "TRAVEL",
  "GAMING",
  "CRYPTO_ONRAMP",
  "MONEY_TRANSFER",
  "SUBSCRIPTION",
] as const;

const COUNTRIES = ["US", "GB", "DE", "SG", "BR", "NG", "JP"] as const;

const TRANSACTION_STATUSES: readonly TransactionStatus[] = [
  "AUTHORIZED",
  "DECLINED",
  "PENDING",
  "REVERSED",
];

const REASON_LIBRARY: readonly Omit<ReasonCode, "contribution">[] = [
  { code: "R-VEL-01", label: "Velocity: 5+ attempts in 10 minutes", source: "RULE" },
  { code: "R-GEO-04", label: "Country mismatch with account profile", source: "RULE" },
  { code: "R-AMT-02", label: "Amount above account rolling maximum", source: "RULE" },
  { code: "R-DEV-07", label: "Unrecognised device fingerprint", source: "RULE" },
  { code: "M-EMB-11", label: "Merchant-category embedding anomaly", source: "MODEL" },
  { code: "M-SEQ-03", label: "Unusual transaction sequence for account", source: "MODEL" },
  { code: "M-TIM-05", label: "Off-pattern hour of day", source: "MODEL" },
  { code: "M-NET-08", label: "Shared-device cluster signal", source: "MODEL" },
];

const ANALYSTS = ["analyst.a1", "analyst.b2", "analyst.c3", "admin.z9"] as const;

function bandFor(finalScore: number): RiskBand {
  if (finalScore >= 90) return "CRITICAL";
  if (finalScore >= 70) return "HIGH";
  if (finalScore >= 40) return "MEDIUM";
  return "LOW";
}

function priorityFor(band: RiskBand): AlertPriority {
  switch (band) {
    case "CRITICAL":
      return "P1";
    case "HIGH":
      return "P2";
    case "MEDIUM":
      return "P3";
    default:
      return "P4";
  }
}

function amountString(rng: () => number): string {
  const units = Math.floor(rng() * 980_00) + 500; // cents-ish, integer math only
  const whole = Math.floor(units / 100);
  const cents = units % 100;
  return `${whole}.${pad(cents, 2)}`;
}

function buildAssessment(rng: () => number, index: number, occurredAt: string): RiskAssessment {
  const ruleScore = Math.floor(rng() * 100);
  const modelScore = Math.floor(rng() * 100);
  const finalScore = Math.round(ruleScore * 0.4 + modelScore * 0.6);
  const reasonCount = 2 + Math.floor(rng() * 3);
  const chosen: ReasonCode[] = [];
  const used = new Set<string>();
  while (chosen.length < reasonCount) {
    const candidate = pick(rng, REASON_LIBRARY);
    if (used.has(candidate.code)) continue;
    used.add(candidate.code);
    const contribution = Math.floor(rng() * 24) + 3;
    chosen.push({ ...candidate, contribution: `${contribution}.0` });
  }
  return {
    assessmentId: `ASM-${pad(index, 6)}`,
    ruleScore,
    modelScore,
    finalScore,
    riskBand: bandFor(finalScore),
    modelVersion: MODEL_VERSION,
    featureVersion: FEATURE_VERSION,
    policyVersion: POLICY_VERSION,
    reasonCodes: chosen,
    scoringLatencyMs: 18 + Math.floor(rng() * 120),
    assessedAt: occurredAt,
  };
}

function buildTransaction(index: number): Transaction {
  const rng = mulberry32(1_000 + index * 7);
  const occurredAt = new Date(MOCK_EPOCH - index * 47_000).toISOString();
  return {
    transactionId: `TXN-${pad(index, 6)}`,
    accountId: `ACC-${pad((index % 120) + 1, 6)}`,
    merchantId: `MER-${pad((index % 48) + 1, 4)}`,
    merchantCategory: pick(rng, CATEGORIES),
    channel: pick(rng, CHANNELS),
    money: { amount: amountString(rng), currency: pick(rng, ["USD", "EUR", "GBP"] as const) },
    status: pick(rng, TRANSACTION_STATUSES),
    countryCode: pick(rng, COUNTRIES),
    deviceId: `DEV-${pad((index % 200) + 1, 5)}`,
    occurredAt,
    correlationId: `corr-${pad(index, 6)}-${pad(index % 97, 4)}`,
    assessment: buildAssessment(rng, index, occurredAt),
  };
}

export const TRANSACTIONS: Transaction[] = Array.from({ length: 480 }, (_, i) =>
  buildTransaction(i + 1),
);

const TRANSACTION_INDEX = new Map(TRANSACTIONS.map((t) => [t.transactionId, t]));

export function findTransaction(transactionId: string): Transaction | undefined {
  return TRANSACTION_INDEX.get(transactionId);
}

function statusFor(rng: () => number, index: number): AlertStatus {
  if (index % 9 === 0) return "NEW";
  return pick(rng, ALERT_STATUSES);
}

function buildAlert(index: number): AlertSummary {
  const rng = mulberry32(50_000 + index * 13);
  const transaction = TRANSACTIONS[(index * 3) % TRANSACTIONS.length] as Transaction;
  const status = statusFor(rng, index);
  const ageMinutes = 3 + Math.floor(rng() * 4_000);
  return {
    alertId: `ALR-${pad(index, 6)}`,
    transactionId: transaction.transactionId,
    accountId: transaction.accountId,
    priority: priorityFor(transaction.assessment.riskBand),
    status,
    riskBand: transaction.assessment.riskBand,
    finalScore: transaction.assessment.finalScore,
    topReasonCode: transaction.assessment.reasonCodes[0]?.code ?? "R-VEL-01",
    assignee: status === "NEW" ? null : pick(rng, ANALYSTS),
    createdAt: new Date(MOCK_EPOCH - ageMinutes * 60_000).toISOString(),
    ageMinutes,
    money: transaction.money,
  };
}

export const ALERTS: AlertSummary[] = Array.from({ length: 164 }, (_, i) => buildAlert(i + 1));

const ALERT_INDEX = new Map(ALERTS.map((a) => [a.alertId, a]));

export function findAlertSummary(alertId: string): AlertSummary | undefined {
  return ALERT_INDEX.get(alertId);
}

export function alertForTransaction(transactionId: string): AlertSummary | undefined {
  return ALERTS.find((a) => a.transactionId === transactionId);
}

export function buildAlertDetail(summary: AlertSummary): AlertDetail {
  const transaction = findTransaction(summary.transactionId) as Transaction;
  const timeline: TimelineEvent[] = [
    {
      eventId: `${summary.alertId}-E1`,
      kind: "CREATED",
      actor: "scoring-service",
      message: `Alert opened from assessment ${transaction.assessment.assessmentId} (final score ${transaction.assessment.finalScore}).`,
      occurredAt: summary.createdAt,
    },
  ];
  if (summary.assignee) {
    timeline.push({
      eventId: `${summary.alertId}-E2`,
      kind: "ASSIGNED",
      actor: "queue-router",
      message: `Assigned to ${summary.assignee}.`,
      occurredAt: new Date(Date.parse(summary.createdAt) + 240_000).toISOString(),
    });
  }
  if (summary.status !== "NEW") {
    timeline.push({
      eventId: `${summary.alertId}-E3`,
      kind: "STATUS_CHANGE",
      actor: summary.assignee ?? "analyst.a1",
      message: `Status set to ${summary.status}.`,
      occurredAt: new Date(Date.parse(summary.createdAt) + 900_000).toISOString(),
    });
  }

  const audit: AuditEntry[] = timeline.map((event, i) => ({
    entryId: `${summary.alertId}-A${i + 1}`,
    action: event.kind,
    actor: event.actor,
    actorRole: event.actor.startsWith("admin") ? "ADMINISTRATOR" : "ANALYST",
    occurredAt: event.occurredAt,
    detail: event.message,
  }));

  return {
    ...summary,
    transaction,
    timeline,
    audit,
    notes:
      summary.status === "NEW"
        ? []
        : [
            {
              noteId: `${summary.alertId}-N1`,
              author: summary.assignee ?? "analyst.a1",
              body: "Synthetic case note: reviewed velocity pattern against account baseline.",
              createdAt: new Date(Date.parse(summary.createdAt) + 1_200_000).toISOString(),
            },
          ],
  };
}

export function relatedActivity(accountId: string, excludeId: string): RelatedAccountActivity[] {
  return TRANSACTIONS.filter((t) => t.accountId === accountId && t.transactionId !== excludeId)
    .slice(0, 8)
    .map((t) => ({
      transactionId: t.transactionId,
      occurredAt: t.occurredAt,
      money: t.money,
      merchantId: t.merchantId,
      riskBand: t.assessment.riskBand,
      status: t.status,
    }));
}

export function throughputSeries(): ThroughputPoint[] {
  return Array.from({ length: 24 }, (_, i) => {
    const rng = mulberry32(900 + i);
    const scored = 1_200 + Math.floor(rng() * 900);
    return {
      bucketStart: new Date(MOCK_EPOCH - (23 - i) * 3_600_000).toISOString(),
      scored,
      alerted: Math.floor(scored * 0.04) + Math.floor(rng() * 12),
    };
  });
}

export const PIPELINE_HEALTH: PipelineHealth = {
  consumerGroups: [
    { groupId: "scoring-consumer", topic: "transactions.raw", lagMessages: 142 },
    { groupId: "alerting-consumer", topic: "assessments.scored", lagMessages: 18 },
    { groupId: "audit-consumer", topic: "alerts.events", lagMessages: 0 },
  ],
  dlqDepth: 7,
  dlqTopic: "transactions.raw.dlq",
};

export const HEALTH_COMPONENTS: ComponentHealth[] = [
  {
    componentId: "api",
    name: "Console API",
    state: "OPERATIONAL",
    detail: "All read endpoints responding within target window.",
    lastCheckedAt: new Date(MOCK_EPOCH - 30_000).toISOString(),
  },
  {
    componentId: "scoring",
    name: "Scoring service",
    state: "DEGRADED",
    detail: "Elevated p99 latency on model inference path.",
    lastCheckedAt: new Date(MOCK_EPOCH - 45_000).toISOString(),
  },
  {
    componentId: "kafka",
    name: "Kafka cluster",
    state: "OPERATIONAL",
    detail: "3 brokers in sync; consumer lag within bounds.",
    lastCheckedAt: new Date(MOCK_EPOCH - 20_000).toISOString(),
  },
  {
    componentId: "database",
    name: "PostgreSQL",
    state: "OPERATIONAL",
    detail: "Primary healthy; replica lag under 1s.",
    lastCheckedAt: new Date(MOCK_EPOCH - 25_000).toISOString(),
  },
];

export const MODEL_POLICY: ModelPolicySnapshot = {
  modelVersion: MODEL_VERSION,
  featureVersion: FEATURE_VERSION,
  policyVersion: POLICY_VERSION,
  trainedAt: "2026-07-19T08:30:00.000Z",
  metrics: [
    { key: "dataset", label: "Evaluation dataset", value: "synthetic-eval-2026.07" },
    { key: "rows", label: "Evaluation rows", value: "250,000 (synthetic)" },
    { key: "threshold", label: "Alerting threshold", value: "final score >= 40" },
    { key: "refresh", label: "Feature refresh cadence", value: "hourly batch + streaming" },
  ],
  thresholds: [
    { riskBand: "LOW", minFinalScore: 0, action: "Score and store only" },
    { riskBand: "MEDIUM", minFinalScore: 40, action: "Open alert at P3" },
    { riskBand: "HIGH", minFinalScore: 70, action: "Open alert at P2" },
    { riskBand: "CRITICAL", minFinalScore: 90, action: "Open alert at P1 and page on-call" },
  ],
  limitations: [
    "All figures on this screen describe a synthetic demonstration model. No production or real-world performance is represented.",
    "Scores are not a determination of fraud. They are an ordering signal for human review.",
    "The model card, training data lineage, and evaluation methodology are owned by the external backend repository, not by this console.",
    "Thresholds shown here are read-only in this console; policy changes are made in the backend policy service.",
  ],
};

export const REPORTS: ReportsSnapshot = {
  windowLabel: "Last 14 days (synthetic)",
  dailyAlertTrend: Array.from({ length: 14 }, (_, i) => {
    const rng = mulberry32(7_000 + i);
    const alerts = 60 + Math.floor(rng() * 70);
    return {
      date: new Date(MOCK_EPOCH - (13 - i) * 86_400_000).toISOString().slice(0, 10),
      alerts,
      escalated: Math.floor(alerts * 0.18) + Math.floor(rng() * 6),
    };
  }),
  riskBands: [],
  feedback: [
    { outcome: "Confirmed suspicious", count: 118 },
    { outcome: "Dismissed — false positive", count: 264 },
    { outcome: "Escalated to investigations", count: 73 },
    { outcome: "Awaiting analyst decision", count: 41 },
  ],
};
