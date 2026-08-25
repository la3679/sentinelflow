import type { AlertStatus, HealthState, Role, RiskBand } from "./types";

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

export const ROLE_LABELS: Record<Role, string> = {
  ANALYST: "Analyst",
  ADMINISTRATOR: "Administrator",
  AUDITOR: "Auditor (read-only)",
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
