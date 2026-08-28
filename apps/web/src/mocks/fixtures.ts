/**
 * TEMPORARY deterministic fixtures for the four screens with no server.
 *
 * Every value here is fictional synthetic data generated from a fixed seed, so
 * the console renders identically on every load. It stands in for the overview,
 * the reports screen, the model and policy screen, and system health — the four
 * endpoints this console invents. `docs/frontend/API_MIGRATION_AUDIT.md` records
 * what each one wants and what has to be decided before it can be real.
 *
 * **Nothing here backs the alert queue or the transaction list any more.** Those
 * read the API. What remains is shaped like the contract even so, because a
 * fixture in a shape the API does not serve is a shape somebody writes a screen
 * against.
 */
import type {
  Alert,
  AlertPriority,
  AlertStatus,
  ComponentHealth,
  ModelPolicySnapshot,
  PipelineHealth,
  ReportsSnapshot,
  RiskBand,
  ThroughputPoint,
} from "@/domain/types";

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

/**
 * A UUID-shaped identifier drawn from the seeded generator.
 *
 * The API's keys are UUIDs and its handles are references, and a fixture that
 * used a readable string for both would hide the two-field design every screen
 * has to respect: display the reference, route on the identifier.
 */
function uuidFrom(rng: () => number): string {
  const hex = (count: number): string =>
    Array.from({ length: count }, () => Math.floor(rng() * 16).toString(16)).join("");
  return `${hex(8)}-${hex(4)}-4${hex(3)}-a${hex(3)}-${hex(12)}`;
}

const REASON_CODES = [
  "R_VELOCITY_10M",
  "R_GEO_MISMATCH",
  "R_AMOUNT_ABOVE_BASELINE",
  "R_UNKNOWN_DEVICE",
  "R_SMALL_HOURS",
  "R_BALANCE_DRAIN",
] as const;

const STATUSES: readonly AlertStatus[] = [
  "NEW",
  "IN_REVIEW",
  "ESCALATED",
  "CONFIRMED_SUSPICIOUS",
  "DISMISSED_FALSE_POSITIVE",
  "CLOSED",
];

function bandFor(finalScore: number): RiskBand {
  if (finalScore >= 90) return "CRITICAL";
  if (finalScore >= 70) return "HIGH";
  if (finalScore >= 40) return "MEDIUM";
  return "LOW";
}

/** The same mapping `priorityByBand` makes on the server, for fixture data only. */
function priorityFor(band: RiskBand): AlertPriority {
  switch (band) {
    case "CRITICAL":
      return "URGENT";
    case "HIGH":
      return "HIGH";
    case "MEDIUM":
      return "MEDIUM";
    default:
      return "LOW";
  }
}

function buildAlert(index: number): Alert {
  const rng = mulberry32(50_000 + index * 13);
  const finalScore = 62 + Math.floor(rng() * 38);
  const riskBand = bandFor(finalScore);
  const status = index % 9 === 0 ? "NEW" : pick(rng, STATUSES);
  const ageMinutes = 3 + Math.floor(rng() * 4_000);
  const createdAt = new Date(MOCK_EPOCH - ageMinutes * 60_000).toISOString();
  const closed = status === "CLOSED";
  return {
    alertId: uuidFrom(rng),
    alertReference: `ALT-${pad(index, 4)}`,
    transactionId: uuidFrom(rng),
    assessmentId: uuidFrom(rng),
    status,
    priority: priorityFor(riskBand),
    assigneeId: status === "NEW" ? null : uuidFrom(rng),
    summary: `${riskBand} risk ${finalScore} on TXN-${pad(index * 3, 6)} — ${pick(rng, REASON_CODES)}`,
    riskBand,
    finalScore,
    version: status === "NEW" ? 0 : 1 + Math.floor(rng() * 4),
    // Empty deliberately. The overview lists alerts and offers no move on any of
    // them, and a fixture that guessed at this field would be the second copy of
    // the state machine this migration deleted.
    legalTargets: [],
    createdAt,
    updatedAt: new Date(MOCK_EPOCH - Math.floor(ageMinutes / 2) * 60_000).toISOString(),
    closedAt: closed ? new Date(MOCK_EPOCH - 60_000).toISOString() : null,
  };
}

export const ALERTS: Alert[] = Array.from({ length: 164 }, (_, i) => buildAlert(i + 1));

/**
 * A band distribution over scored transactions, generated directly.
 *
 * It is not derived from the alerts: most scored transactions never raise one,
 * so counting alerts by band would show a distribution shaped like the alerting
 * threshold rather than like the traffic.
 */
export const SCORED_BAND_COUNTS: Record<RiskBand, number> = {
  LOW: 14_920,
  MEDIUM: 4_260,
  HIGH: 812,
  CRITICAL: 143,
};

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
    { riskBand: "MEDIUM", minFinalScore: 40, action: "Open alert at medium priority" },
    { riskBand: "HIGH", minFinalScore: 70, action: "Open alert at high priority" },
    { riskBand: "CRITICAL", minFinalScore: 90, action: "Open alert at urgent priority" },
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
