/**
 * SentinelFlow domain model, as `contracts/openapi/sentinelflow-api.yaml`
 * describes it.
 *
 * The contract is authoritative (`CLAUDE.md`), so where this file and the
 * contract disagree, this file is wrong. Everything here describes SYNTHETIC
 * data only.
 *
 * <h2>Two halves, and the second one is temporary</h2>
 *
 * Below the divider are the shapes of four endpoints **that do not exist** —
 * the overview, the reports screen, the model and policy screen, and system
 * health. They are invented by this console and resolved from fixtures.
 * `docs/frontend/API_MIGRATION_AUDIT.md` records what each one wants and what
 * the decision between an API addition and a client-side composition is.
 * Nothing above the divider is a fixture shape.
 *
 * <h2>What is deliberately absent</h2>
 *
 * There is **no copy of the alert state machine here.** An alert carries
 * `legalTargets` — the moves this reader may make — and a client renders its
 * transition controls from that and nothing else. A second copy would disagree
 * with `AlertTransitions.java` the moment either changed, which is what it did:
 * it offered two moves the server answers `409` to and hid four legal ones.
 *
 * There is also **no `actor` field on any mutation.** The actor is the `sub`
 * claim of the bearer token (ADR-0012). A client that names its own actor is a
 * forgeable audit trail.
 */

// ---------------------------------------------------------------------------
// The contract
// ---------------------------------------------------------------------------

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

export const ALERT_PRIORITIES = ["LOW", "MEDIUM", "HIGH", "URGENT"] as const;
export type AlertPriority = (typeof ALERT_PRIORITIES)[number];

/**
 * The roles an operator can hold and sign in with.
 *
 * `SYSTEM` is deliberately **not** here. It is a role an *action* can have been
 * taken under, never one a person signs in as, and putting it in this list
 * would let a login response naming it be accepted. See {@link ActorRole}.
 */
export const ROLES = ["ANALYST", "ADMINISTRATOR", "AUDITOR"] as const;
export type Role = (typeof ROLES)[number];

/**
 * The roles an entry in an alert's history can be attributed to.
 *
 * The alert-raising path acts as the system principal, so a console that typed
 * this as the three human roles could not render its own audit trail.
 */
export const ACTOR_ROLES = [...ROLES, "SYSTEM"] as const;
export type ActorRole = (typeof ACTOR_ROLES)[number];

export const PROCESSING_STATUSES = ["PENDING", "ASSESSED", "FAILED"] as const;
/**
 * How far *this system* has got with a transaction.
 *
 * Not what a payment switch decided — SentinelFlow scores and never authorizes,
 * declines or reverses anything, and a console that displayed those words would
 * be describing a product this is not.
 */
export type ProcessingStatus = (typeof PROCESSING_STATUSES)[number];

export const ALERT_ACTION_TYPES = [
  "CREATED",
  "ASSIGNED",
  "UNASSIGNED",
  "TRANSITIONED",
  "NOTE_ADDED",
  "PRIORITY_CHANGED",
] as const;
export type AlertActionType = (typeof ALERT_ACTION_TYPES)[number];

export const FEEDBACK_LABELS = ["TRUE_POSITIVE", "FALSE_POSITIVE", "INCONCLUSIVE"] as const;
export type FeedbackLabel = (typeof FEEDBACK_LABELS)[number];

/**
 * The only shape in which an amount appears in this API.
 *
 * `value` is a decimal **string** and is never parsed into a number: a float
 * cannot hold `NUMERIC(19,4)` and money is not a float anywhere in this
 * project (ADR-0007).
 */
export interface Amount {
  value: string;
  currency: string;
}

/** Paging metadata. `totalElements` is counted, never estimated. */
export interface PageMeta {
  /** Zero-based, as the API numbers pages. */
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** Every paged response in this API has this shape. */
export interface Page<T> {
  content: T[];
  page: PageMeta;
}

export interface ReasonCode {
  code: string;
  /** Human-readable, and the only place a device handle or a ratio may appear. */
  description: string;
  /**
   * How much this factor pushed the score, on the scale its `source` uses.
   *
   * A `RULE` contribution is on the 0-to-100 scale and the rule reasons sum to
   * `ruleScore`. A `MODEL` contribution is the estimator's own signed
   * decomposition: it sums to nothing, has no natural bound, and is comparable
   * only within one `modelVersion`.
   */
  contribution: number;
  source: "RULE" | "MODEL";
}

export interface RiskAssessment {
  assessmentId: string;
  transactionId: string;
  ruleScore: number;
  /** Null when scoring was unavailable and the assessment degraded to the rules. */
  modelScore: number | null;
  finalScore: number;
  riskBand: RiskBand;
  /** Explicit rather than inferred from a null `modelScore`. */
  degraded: boolean;
  modelVersion: string | null;
  featureVersion: string | null;
  /** Always present. The rules are the half that always runs. */
  rulesetVersion: string;
  policyVersion: string;
  reasonCodes: ReasonCode[];
  scoringLatencyMs?: number;
  assessedAt: string;
}

export interface Transaction {
  /** The key. Routed on, never displayed. */
  transactionId: string;
  /** The handle a person uses — `TXN-000517`. Displayed, never routed on. */
  transactionReference: string;
  accountReference: string;
  merchantReference: string;
  merchantCategoryCode?: string;
  /**
   * `PURCHASE`, `CARD_NOT_PRESENT` and so on.
   *
   * Typed as strings rather than as unions because the contract's response
   * schema does not enumerate them — only the ingestion request does. Narrowing
   * a field the contract leaves open would put a copy of a server rule in this
   * client, which is the mistake `legalTargets` exists to end.
   */
  type?: string;
  channel?: string;
  amount: Amount;
  originCountry?: string;
  occurredAt: string;
  /** When this system received it, distinct from when it happened. */
  ingestedAt: string;
  processingStatus: ProcessingStatus;
  /** Null until an assessment exists. */
  riskBand?: RiskBand | null;
}

export interface Alert {
  alertId: string;
  /** The handle a person uses — `ALT-0001`. A queue row showing a UUID is unreadable. */
  alertReference: string;
  transactionId: string;
  assessmentId: string;
  status: AlertStatus;
  priority: AlertPriority;
  /**
   * A UUID, and nothing resolves it to a name yet.
   *
   * The audit records that as an open decision rather than a mapping detail —
   * either the alert grows a display name or there is a small user lookup.
   */
  assigneeId: string | null;
  /**
   * The person `assigneeId` names, or null when the alert is unassigned.
   *
   * Both are null together. An identifier that resolved to nothing publishes the
   * id with a null assignee, which is the honest answer rather than a
   * placeholder — so a screen renders from this and falls back to the id.
   */
  assignee: AlertAssignee | null;
  /**
   * Built from the band, the score, the transaction reference and the leading
   * reason **code** — never a reason's generated description, which may name a
   * device handle and is right on a detail page and wrong on a queue row.
   */
  summary: string;
  riskBand: RiskBand;
  finalScore: number;
  /**
   * Optimistic-lock version, passed back as `expectedVersion` to mutate.
   *
   * **Opaque.** Compare it for equality and never read meaning into its
   * magnitude. A new alert is at 0, which is a legitimate value to send.
   */
  version: number;
  /**
   * The statuses **this caller** may move the alert to next.
   *
   * A property of the alert and its reader together: `CLOSED` is an
   * administrator's alone, so an analyst reading the same alert is offered one
   * fewer target, and an auditor is offered none. Render the transition
   * controls from this and nothing else.
   */
  legalTargets: AlertStatus[];
  createdAt: string;
  updatedAt: string;
  closedAt?: string | null;
}

/** One entry in an alert's audit trail. Notes are entries too. */
export interface AlertAction {
  actionId: string;
  alertId: string;
  actorId: string;
  actorRole: ActorRole;
  actionType: AlertActionType;
  previousStatus?: AlertStatus | null;
  newStatus?: AlertStatus | null;
  note?: string | null;
  correlationId?: string;
  occurredAt: string;
}

export interface AlertFeedback {
  feedbackId: string;
  /** The decision this verdict is about. The label belongs to the assessment, not to the alert. */
  assessmentId: string;
  alertId: string | null;
  actorId: string;
  label: FeedbackLabel;
  reason?: string | null;
  createdAt: string;
}

/** Counts over one window, with every key of every enum present, zeroes included. */
export interface AlertSummaryReport {
  from: string;
  to: string;
  total: number;
  /** Derived from `closedAt` being absent, not from a list of statuses. */
  open: number;
  closed: number;
  byStatus: Record<AlertStatus, number>;
  byPriority: Record<AlertPriority, number>;
  byRiskBand: Record<RiskBand, number>;
}

/**
 * The person an alert is with.
 *
 * Resolved by the API rather than here, so a queue row can render somebody
 * without this console loading the operator directory before it draws a row —
 * and so that a client and the API cannot disagree about an identifier that
 * resolves to nothing.
 *
 * `username` travels beside `displayName` because a display name is not unique
 * and a username is.
 */
export interface AlertAssignee {
  operatorId: string;
  username: string;
  displayName: string;
}

/**
 * An operator an alert may be given to.
 *
 * From `GET /operators`, which lists the active operators holding a role that
 * can work an alert. **The roles here authorize nothing**: they are for saying
 * "administrator" beside a name. Every authorization decision is the API's.
 */
export interface Operator {
  operatorId: string;
  username: string;
  displayName: string;
  roles: Role[];
}

/**
 * What is scoring, and what this service does with the score.
 *
 * Two halves from two owners, composed by the API (ADR-0014 §1). Every field of
 * the model half is null when the scoring service could not be reached, and the
 * policy half never is — `modelAvailable` says which case this is rather than
 * leaving a screen to infer it from a null.
 */
export interface ModelMetadata {
  modelVersion: string | null;
  featureVersion: string | null;
  /** This service's own, and always present. */
  policyVersion: string;
  algorithm: string | null;
  trainedAt: string | null;
  /** The checksum of the artifact actually loaded, so "which model is running" is answerable. */
  artifactSha256: string | null;
  modelAvailable: boolean;
  modelUnavailableReason: string | null;
  metrics: ModelMetrics | null;
  thresholds: PolicyThreshold[];
  limitations: string[];
}

/**
 * What the model was measured at, on its own synthetic evaluation split.
 *
 * **Accuracy is absent**, here and in both contracts. The classes are extremely
 * imbalanced, so a model that answers "not suspicious" to everything scores well
 * on it and is worthless — and a figure that exists is one somebody quotes.
 */
export interface ModelMetrics {
  precision: number;
  recall: number;
  f1: number;
  averagePrecision: number;
  falsePositiveRate: number;
  /**
   * The model's own recommended operating point, which is **not** the threshold
   * that ran: the API applies its own policy to a final score that also folds in
   * a rule score the model never saw.
   */
  operatingThreshold: number;
}

/** One band, the score it starts at, and what happens to a transaction in it. */
export interface PolicyThreshold {
  riskBand: RiskBand;
  minFinalScore: number;
  raisesAlert: boolean;
  /** Null for a band that does not alert. */
  priority: AlertPriority | null;
}

export type ComponentState = "OPERATIONAL" | "DEGRADED" | "OUTAGE";

/**
 * Whether each part of the stack is answering, as the API can see it.
 *
 * There is no `UNKNOWN`: a component in this list was asked. Consumer lag and
 * dead-letter depth are deliberately absent: both are measured, and they are
 * measured where they are produced. They belong to Kafka and reach Prometheus,
 * which has the dashboards and the alerting rules over them; this endpoint
 * answers whether the components the console depends on are responding now.
 */
export interface SystemHealth {
  components: {
    componentId: string;
    name: string;
    state: ComponentState;
    detail: string;
  }[];
  checkedAt: string;
}

// ---------------------------------------------------------------------------

/** UX-only permission hint. Real authorization lives in the API. */
export function canMutate(role: Role): boolean {
  return role !== "AUDITOR";
}
