import {
  AlertOctagon,
  AlertTriangle,
  Ban,
  CheckCircle2,
  CircleDot,
  Clock,
  Eye,
  MinusCircle,
  ShieldAlert,
  ShieldQuestion,
  TrendingUp,
  type LucideIcon,
} from "lucide-react";

import { cn } from "@/lib/utils";
import { ALERT_STATUS_LABELS, HEALTH_LABELS, RISK_BAND_LABELS } from "@/domain/labels";
import type { AlertPriority, AlertStatus, HealthState, RiskBand } from "@/domain/types";

const chipBase =
  "inline-flex items-center gap-1.5 rounded-md border px-2 py-0.5 text-xs font-medium whitespace-nowrap";

const RISK_ICONS: Record<RiskBand, LucideIcon> = {
  LOW: MinusCircle,
  MEDIUM: AlertTriangle,
  HIGH: ShieldAlert,
  CRITICAL: AlertOctagon,
};

const RISK_CLASSES: Record<RiskBand, string> = {
  LOW: "bg-risk-low text-risk-low-foreground border-border-strong",
  MEDIUM: "bg-risk-medium text-risk-medium-foreground border-risk-medium",
  HIGH: "bg-risk-high text-risk-high-foreground border-risk-high",
  CRITICAL: "bg-risk-critical text-risk-critical-foreground border-risk-critical",
};

/** Risk is always communicated by icon + text label, never by color alone. */
export function RiskBandChip({
  band,
  className,
}: {
  band: RiskBand;
  className?: string | undefined;
}) {
  const Icon = RISK_ICONS[band];
  return (
    <span className={cn(chipBase, RISK_CLASSES[band], className)}>
      <Icon aria-hidden="true" className="size-3.5 shrink-0" />
      {RISK_BAND_LABELS[band]}
    </span>
  );
}

const STATUS_ICONS: Record<AlertStatus, LucideIcon> = {
  NEW: CircleDot,
  IN_REVIEW: Eye,
  ESCALATED: TrendingUp,
  CONFIRMED_SUSPICIOUS: ShieldAlert,
  DISMISSED_FALSE_POSITIVE: Ban,
  CLOSED: CheckCircle2,
};

const STATUS_CLASSES: Record<AlertStatus, string> = {
  NEW: "bg-surface-raised text-foreground border-border-strong",
  IN_REVIEW: "bg-surface-raised text-foreground border-primary/60",
  ESCALATED: "bg-risk-high text-risk-high-foreground border-risk-high",
  CONFIRMED_SUSPICIOUS: "bg-risk-critical text-risk-critical-foreground border-risk-critical",
  DISMISSED_FALSE_POSITIVE: "bg-muted text-muted-foreground border-border-strong",
  CLOSED: "bg-muted text-muted-foreground border-border-strong",
};

export function AlertStatusChip({
  status,
  className,
}: {
  status: AlertStatus;
  className?: string | undefined;
}) {
  const Icon = STATUS_ICONS[status];
  return (
    <span className={cn(chipBase, STATUS_CLASSES[status], className)}>
      <Icon aria-hidden="true" className="size-3.5 shrink-0" />
      {ALERT_STATUS_LABELS[status]}
    </span>
  );
}

const HEALTH_ICONS: Record<HealthState, LucideIcon> = {
  OPERATIONAL: CheckCircle2,
  DEGRADED: AlertTriangle,
  OUTAGE: AlertOctagon,
  UNKNOWN: ShieldQuestion,
};

const HEALTH_CLASSES: Record<HealthState, string> = {
  OPERATIONAL: "bg-surface-raised text-foreground border-border-strong",
  DEGRADED: "bg-risk-medium text-risk-medium-foreground border-risk-medium",
  OUTAGE: "bg-risk-critical text-risk-critical-foreground border-risk-critical",
  UNKNOWN: "bg-muted text-muted-foreground border-border-strong",
};

export function HealthChip({
  state,
  className,
}: {
  state: HealthState;
  className?: string | undefined;
}) {
  const Icon = HEALTH_ICONS[state];
  return (
    <span className={cn(chipBase, HEALTH_CLASSES[state], className)}>
      <Icon aria-hidden="true" className="size-3.5 shrink-0" />
      {HEALTH_LABELS[state]}
    </span>
  );
}

const PRIORITY_CLASSES: Record<AlertPriority, string> = {
  P1: "bg-risk-critical text-risk-critical-foreground border-risk-critical",
  P2: "bg-risk-high text-risk-high-foreground border-risk-high",
  P3: "bg-risk-medium text-risk-medium-foreground border-risk-medium",
  P4: "bg-surface-raised text-foreground border-border-strong",
};

export function PriorityChip({ priority }: { priority: AlertPriority }) {
  return (
    <span className={cn(chipBase, "tabular", PRIORITY_CLASSES[priority])}>
      <Clock aria-hidden="true" className="size-3.5 shrink-0" />
      Priority {priority.slice(1)}
    </span>
  );
}
