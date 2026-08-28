import type {
  ActorRole,
  AlertActionType,
  AlertPriority,
  AlertStatus,
  HealthState,
  ProcessingStatus,
  RiskBand,
} from "./types";

export const RISK_BAND_LABELS: Record<RiskBand, string> = {
  LOW: "Low risk",
  MEDIUM: "Medium risk",
  HIGH: "High risk",
  CRITICAL: "Critical risk",
};

export const ALERT_STATUS_LABELS: Record<AlertStatus, string> = {
  NEW: "New",
  IN_REVIEW: "In review",
  ESCALATED: "Escalated",
  CONFIRMED_SUSPICIOUS: "Confirmed suspicious",
  DISMISSED_FALSE_POSITIVE: "Dismissed — false positive",
  CLOSED: "Closed",
};

/**
 * Every role an action can be attributed to, which is a superset of the roles
 * an operator can sign in as. `SYSTEM` appears on the history row the
 * alert-raising path writes, and a console that could not name it would render
 * a blank where the audit trail says who acted.
 */
export const ROLE_LABELS: Record<ActorRole, string> = {
  ANALYST: "Analyst",
  ADMINISTRATOR: "Administrator",
  AUDITOR: "Auditor (read-only)",
  SYSTEM: "System",
};

/**
 * Priority as the API names it, which is severity rather than a rank.
 *
 * The console used to say P1 to P4. That implied an ordering convention the API
 * does not share, and `priorityByBand` on the server maps a risk band to these
 * four words.
 */
export const ALERT_PRIORITY_LABELS: Record<AlertPriority, string> = {
  URGENT: "Urgent",
  HIGH: "High",
  MEDIUM: "Medium",
  LOW: "Low",
};

/**
 * How far this system has got with a transaction.
 *
 * Not a payment decision: SentinelFlow scores and never authorizes or declines.
 */
export const PROCESSING_STATUS_LABELS: Record<ProcessingStatus, string> = {
  PENDING: "Awaiting assessment",
  ASSESSED: "Assessed",
  FAILED: "Processing failed",
};

export const ALERT_ACTION_LABELS: Record<AlertActionType, string> = {
  CREATED: "Opened",
  ASSIGNED: "Assigned",
  UNASSIGNED: "Released",
  TRANSITIONED: "Status changed",
  NOTE_ADDED: "Note added",
  PRIORITY_CHANGED: "Priority changed",
};

export const HEALTH_LABELS: Record<HealthState, string> = {
  OPERATIONAL: "Operational",
  DEGRADED: "Degraded",
  OUTAGE: "Outage",
  UNKNOWN: "Unknown",
};

/**
 * Formats a decimal money string for display without ever converting it to a
 * floating-point number.
 */
export function formatMoney(amount: string, currency: string): string {
  const negative = amount.startsWith("-");
  const raw = negative ? amount.slice(1) : amount;
  const [intPart = "0", fracPart = "00"] = raw.split(".");
  const grouped = intPart.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  return `${negative ? "-" : ""}${grouped}.${fracPart.padEnd(2, "0").slice(0, 2)} ${currency}`;
}

export function formatDateTime(iso: string): string {
  return new Date(iso).toISOString().replace("T", " ").slice(0, 19) + "Z";
}

export function formatAge(minutes: number): string {
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ${minutes % 60}m`;
  return `${Math.floor(hours / 24)}d ${hours % 24}h`;
}

/**
 * How long an alert has been open, from the instant it was raised.
 *
 * The API sends `createdAt` and not an age, which is the right way round: an
 * age computed on the server is stale by the time it is rendered, and how long
 * something has been waiting is a question about *now*. A clock skewed ahead of
 * the server would otherwise produce a negative age, so this floors at zero
 * rather than showing one.
 */
export function formatAgeSince(createdAt: string, now: number = Date.now()): string {
  const elapsed = now - Date.parse(createdAt);
  return formatAge(Math.max(0, Math.floor(elapsed / 60_000)));
}
