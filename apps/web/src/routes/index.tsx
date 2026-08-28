import { createFileRoute, Link } from "@tanstack/react-router";
import { useMemo } from "react";
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";

import { AppShell } from "@/components/app-shell";
import { ChartFrame } from "@/components/chart-frame";
import { AlertStatusChip, PriorityChip, RiskBandChip } from "@/components/chips";
import { QueryState } from "@/components/data-state";
import { PageHeader, Panel, StatTile } from "@/components/panel";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { ALERT_STATUS_LABELS, formatAgeSince, RISK_BAND_LABELS } from "@/domain/labels";
import { ALERT_STATUSES, RISK_BANDS } from "@/domain/types";
import { useGetAlertSummaryQuery, useListAlertsQuery } from "@/api/sentinelApi";

export const Route = createFileRoute("/")({
  head: () => ({
    meta: [
      { title: "Operations overview — SentinelFlow" },
      {
        name: "description",
        content:
          "Open alert workload, risk-band distribution and the queue's next work, over synthetic data.",
      },
      { property: "og:title", content: "Operations overview — SentinelFlow" },
      {
        property: "og:description",
        content: "The open alert workload and what the queue would hand out next.",
      },
    ],
  }),
  component: OverviewPage,
});

/** How much of the queue to show. Bounded, like every list here. */
const RECENT_SIZE = 8;

/** The window the headline counts describe. */
const WINDOW_HOURS = 24;

/**
 * The statuses that mean work is still on somebody's desk.
 *
 * Only used to *label* the tile below the count the API already derived. The
 * count itself is the API's `open`, which comes from whether the alert has been
 * closed rather than from a list of statuses — duplicating that rule here would
 * be a second place to change when a status is added, and the two would
 * disagree.
 */
const OPEN_STATUSES = ["NEW", "IN_REVIEW", "ESCALATED"] as const;

function OverviewPage() {
  // Fixed at mount rather than recomputed each render: a `from` that moved with
  // the clock would change the query key continuously and refetch for ever.
  const { from, to } = useMemo(() => {
    const end = new Date();
    return {
      from: new Date(end.getTime() - WINDOW_HOURS * 3_600_000).toISOString(),
      to: end.toISOString(),
    };
  }, []);

  const summary = useGetAlertSummaryQuery({ from, to });
  const queue = useListAlertsQuery({ page: 0, size: RECENT_SIZE });

  return (
    <AppShell>
      <PageHeader
        title="Operations overview"
        description={`The open alert workload, and what the queue would hand out next. Counts cover the last ${WINDOW_HOURS} hours.`}
      />

      {/*
        Two requests rather than one aggregate endpoint (ADR-0014 §3). An
        aggregate would put a second implementation of risk-band counting beside
        the report that already does it, and the two would disagree the first
        time one changed. The cost is that this screen can be half-loaded, which
        is what the loading and error states below are for.
      */}
      <div className="space-y-6">
        <QueryState
          isLoading={summary.isLoading}
          isError={summary.isError}
          error={summary.error}
          data={summary.data}
          onRetry={() => void summary.refetch()}
          loadingLabel="Counting the last day of alerts"
          loadingRows={4}
        >
          {(counts) => {
            const bands = RISK_BANDS.map((riskBand) => ({
              riskBand,
              count: counts.byRiskBand[riskBand],
            }));
            const openLabel = OPEN_STATUSES.map((status) =>
              ALERT_STATUS_LABELS[status].toLowerCase(),
            ).join(", ");

            return (
              <>
                <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
                  <StatTile
                    label="Alerts raised"
                    value={String(counts.total)}
                    hint={`In the last ${WINDOW_HOURS} hours`}
                  />
                  <StatTile
                    label="Still open"
                    value={String(counts.open)}
                    hint={openLabel}
                    emphasis={counts.open > 0}
                  />
                  <StatTile
                    label="Closed"
                    value={String(counts.closed)}
                    hint="A disposition was recorded"
                  />
                </div>

                <div className="grid gap-4 xl:grid-cols-3">
                  <Panel
                    title="Risk bands"
                    description="Alerts raised in the window, by the band of the assessment behind them."
                    className="xl:col-span-2"
                    bodyClassName="p-4"
                  >
                    <ChartFrame label="Alerts raised per risk band over the last day">
                      <ResponsiveContainer width="100%" height="100%">
                        <BarChart data={bands} margin={{ left: -12, right: 8, top: 8 }}>
                          <CartesianGrid stroke="var(--color-border)" vertical={false} />
                          <XAxis
                            dataKey="riskBand"
                            stroke="var(--color-muted-foreground)"
                            fontSize={11}
                          />
                          <YAxis
                            stroke="var(--color-muted-foreground)"
                            fontSize={11}
                            allowDecimals={false}
                          />
                          <Tooltip
                            contentStyle={{
                              background: "var(--color-popover)",
                              border: "1px solid var(--color-border)",
                              borderRadius: 6,
                              fontSize: 12,
                            }}
                          />
                          <Bar dataKey="count" name="Alerts" fill="var(--color-chart-2)" />
                        </BarChart>
                      </ResponsiveContainer>
                    </ChartFrame>
                  </Panel>

                  <Panel title="By status" bodyClassName="p-4">
                    <ul className="space-y-2 text-sm">
                      {ALERT_STATUSES.map((status) => (
                        <li key={status} className="flex items-center justify-between gap-2">
                          <AlertStatusChip status={status} />
                          <span className="tabular">{counts.byStatus[status]}</span>
                        </li>
                      ))}
                    </ul>
                  </Panel>
                </div>
              </>
            );
          }}
        </QueryState>

        <Panel
          title="Next in the queue"
          description="Open work before closed, then by priority, then oldest first — the queue's own order."
          actions={
            <Link to="/alerts" className="text-sm underline underline-offset-4">
              Work the queue
            </Link>
          }
          bodyClassName="overflow-x-auto"
        >
          <QueryState
            isLoading={queue.isLoading}
            isError={queue.isError}
            error={queue.error}
            data={queue.data}
            onRetry={() => void queue.refetch()}
            loadingLabel="Loading the top of the queue"
            loadingRows={6}
            isEmpty={(page) => page.content.length === 0}
            emptyTitle="Nothing in the queue"
            emptyHint="No alert has been raised yet. Seed the stack, or wait for scored traffic to reach the alerting band."
          >
            {(page) => (
              <Table>
                <caption className="sr-only">The next {RECENT_SIZE} alerts in the queue</caption>
                <TableHeader>
                  <TableRow>
                    <TableHead scope="col">Alert</TableHead>
                    <TableHead scope="col">Priority</TableHead>
                    <TableHead scope="col">Status</TableHead>
                    <TableHead scope="col">Risk band</TableHead>
                    <TableHead scope="col" className="text-right">
                      Final score
                    </TableHead>
                    <TableHead scope="col">Age</TableHead>
                    <TableHead scope="col">Summary</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {page.content.map((alert) => (
                    <TableRow key={alert.alertId}>
                      <TableCell>
                        <Link
                          to="/alerts/$alertId"
                          params={{ alertId: alert.alertId }}
                          className="tabular underline underline-offset-4"
                        >
                          {alert.alertReference}
                        </Link>
                      </TableCell>
                      <TableCell>
                        <PriorityChip priority={alert.priority} />
                      </TableCell>
                      <TableCell>
                        <AlertStatusChip status={alert.status} />
                      </TableCell>
                      <TableCell>
                        <RiskBandChip band={alert.riskBand} />
                      </TableCell>
                      <TableCell className="tabular text-right">{alert.finalScore}</TableCell>
                      <TableCell className="tabular">{formatAgeSince(alert.createdAt)}</TableCell>
                      <TableCell className="max-w-md text-xs">{alert.summary}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </QueryState>
        </Panel>

        {/*
          Throughput per hour, scoring latency percentiles, consumer lag and
          dead-letter depth used to be four charts and four tiles here. Every
          number in them was invented: no process measured any of them, and
          three of the four belong to Prometheus and Kafka rather than to this
          API. They are absent rather than decorative until Phase 7 measures
          them.
        */}
        <Panel
          title="Throughput, latency and pipeline depth"
          description="Not measured yet, so not shown."
          bodyClassName="p-4"
        >
          <p className="text-sm">
            Scored-transaction throughput, scoring latency percentiles, consumer-group lag and
            dead-letter depth are not on this screen.
          </p>
          <p className="mt-2 text-sm text-muted-foreground">
            They arrive in Phase 7 with the metric set, the dashboards and the runbooks that say
            what to do about a number that is climbing. Showing a figure nobody measured would be
            worse than showing none, because somebody would quote it.
          </p>
        </Panel>

        <p className="text-xs text-muted-foreground">
          {RISK_BAND_LABELS.CRITICAL} through {RISK_BAND_LABELS.LOW} describe synthetic assessments
          only. Nothing on this screen describes a real payment portfolio.
        </p>
      </div>
    </AppShell>
  );
}
