import { createFileRoute, Link } from "@tanstack/react-router";
import {
  Bar,
  BarChart,
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";

import { AppShell } from "@/components/app-shell";
import { ChartFrame } from "@/components/chart-frame";
import { AlertStatusChip, PriorityChip, RiskBandChip } from "@/components/chips";
import { QueryState } from "@/components/data-state";
import { FieldRow, PageHeader, Panel, StatTile } from "@/components/panel";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { ALERT_STATUS_LABELS, formatAgeSince, RISK_BAND_LABELS } from "@/domain/labels";
import { useGetOverviewQuery } from "@/api/sentinelApi";

export const Route = createFileRoute("/")({
  head: () => ({
    meta: [
      { title: "Operations overview — SentinelFlow" },
      {
        name: "description",
        content:
          "Throughput, risk-band distribution, open alert counts, scoring latency and pipeline health for the SentinelFlow synthetic dataset.",
      },
      { property: "og:title", content: "Operations overview — SentinelFlow" },
      {
        property: "og:description",
        content:
          "Synthetic transaction throughput, risk bands, alert counts and pipeline health in one console.",
      },
    ],
  }),
  component: OverviewPage,
});

function OverviewPage() {
  const query = useGetOverviewQuery();

  return (
    <AppShell>
      <PageHeader
        title="Operations overview"
        description="Current state of the synthetic scoring pipeline and the open alert workload."
      />
      <QueryState
        isLoading={query.isLoading}
        isError={query.isError}
        error={query.error}
        data={query.data}
        onRetry={() => void query.refetch()}
        loadingLabel="Loading operations overview"
        loadingRows={8}
      >
        {(data) => {
          const openAlerts = data.alertStatuses
            .filter(
              (s) => s.status === "NEW" || s.status === "IN_REVIEW" || s.status === "ESCALATED",
            )
            .reduce((total, s) => total + s.count, 0);
          const totalLag = data.pipeline.consumerGroups.reduce(
            (total, g) => total + g.lagMessages,
            0,
          );

          return (
            <div className="space-y-6">
              <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
                <StatTile
                  label="Open alerts"
                  value={String(openAlerts)}
                  hint="New, in review and escalated"
                />
                <StatTile
                  label="Scoring latency p95"
                  value={`${data.latency.p95Ms} ms`}
                  hint={data.latency.windowLabel}
                />
                <StatTile
                  label="Consumer lag"
                  value={`${totalLag}`}
                  hint="Messages across all consumer groups"
                  emphasis={totalLag > 100}
                />
                <StatTile
                  label="Dead-letter depth"
                  value={String(data.pipeline.dlqDepth)}
                  hint={data.pipeline.dlqTopic}
                  emphasis={data.pipeline.dlqDepth > 0}
                />
              </div>

              <div className="grid gap-4 xl:grid-cols-3">
                <Panel
                  title="Transaction throughput"
                  description="Scored transactions and alerts opened per hour, last 24 hours."
                  className="xl:col-span-2"
                  bodyClassName="p-4"
                >
                  <ChartFrame label="Hourly scored transactions and alerts opened over 24 hours">
                    <ResponsiveContainer width="100%" height="100%">
                      <LineChart data={data.throughput} margin={{ left: -12, right: 8, top: 8 }}>
                        <CartesianGrid stroke="var(--color-border)" vertical={false} />
                        <XAxis
                          dataKey="bucketStart"
                          tickFormatter={(value: string) => value.slice(11, 16)}
                          stroke="var(--color-muted-foreground)"
                          fontSize={11}
                        />
                        <YAxis stroke="var(--color-muted-foreground)" fontSize={11} />
                        <Tooltip
                          contentStyle={{
                            background: "var(--color-popover)",
                            border: "1px solid var(--color-border)",
                            borderRadius: 6,
                            fontSize: 12,
                          }}
                          labelFormatter={(value: string) => `${value.slice(11, 16)} UTC`}
                        />
                        <Line
                          type="monotone"
                          dataKey="scored"
                          name="Scored"
                          stroke="var(--color-chart-1)"
                          dot={false}
                          strokeWidth={2}
                        />
                        <Line
                          type="monotone"
                          dataKey="alerted"
                          name="Alerts opened"
                          stroke="var(--color-chart-4)"
                          dot={false}
                          strokeWidth={2}
                        />
                      </LineChart>
                    </ResponsiveContainer>
                  </ChartFrame>
                </Panel>

                <Panel
                  title="Risk-band distribution"
                  description="Most recent 240 scored transactions."
                  bodyClassName="p-4"
                >
                  <ChartFrame label="Count of recent transactions per risk band">
                    <ResponsiveContainer width="100%" height="100%">
                      <BarChart data={data.riskBands} margin={{ left: -12, right: 8, top: 8 }}>
                        <CartesianGrid stroke="var(--color-border)" vertical={false} />
                        <XAxis
                          dataKey="riskBand"
                          stroke="var(--color-muted-foreground)"
                          fontSize={11}
                        />
                        <YAxis stroke="var(--color-muted-foreground)" fontSize={11} />
                        <Tooltip
                          contentStyle={{
                            background: "var(--color-popover)",
                            border: "1px solid var(--color-border)",
                            borderRadius: 6,
                            fontSize: 12,
                          }}
                        />
                        <Bar dataKey="count" name="Transactions" fill="var(--color-chart-2)" />
                      </BarChart>
                    </ResponsiveContainer>
                  </ChartFrame>
                  <ul className="mt-3 space-y-1 text-xs text-muted-foreground">
                    {data.riskBands.map((band) => (
                      <li key={band.riskBand} className="tabular">
                        {RISK_BAND_LABELS[band.riskBand]}: {band.count}
                      </li>
                    ))}
                  </ul>
                </Panel>
              </div>

              <div className="grid gap-4 xl:grid-cols-3">
                <Panel title="Alerts by status" bodyClassName="p-0">
                  <dl>
                    {data.alertStatuses.map((item) => (
                      <FieldRow key={item.status} label={ALERT_STATUS_LABELS[item.status]}>
                        <span className="tabular">{item.count}</span>
                      </FieldRow>
                    ))}
                  </dl>
                </Panel>

                <Panel title="Scoring latency" bodyClassName="p-0">
                  <dl>
                    <FieldRow label="p50">
                      <span className="tabular">{data.latency.p50Ms} ms</span>
                    </FieldRow>
                    <FieldRow label="p95">
                      <span className="tabular">{data.latency.p95Ms} ms</span>
                    </FieldRow>
                    <FieldRow label="p99">
                      <span className="tabular">{data.latency.p99Ms} ms</span>
                    </FieldRow>
                    <FieldRow label="Window">{data.latency.windowLabel}</FieldRow>
                  </dl>
                </Panel>

                <Panel
                  title="Pipeline health"
                  description="Consumer lag and dead-letter queue depth."
                  bodyClassName="p-0"
                  actions={
                    <Link to="/health" className="text-xs underline underline-offset-4">
                      System health
                    </Link>
                  }
                >
                  <dl>
                    {data.pipeline.consumerGroups.map((group) => (
                      <FieldRow key={group.groupId} label={group.groupId}>
                        <span className="tabular">{group.lagMessages}</span>{" "}
                        <span className="text-muted-foreground">on {group.topic}</span>
                      </FieldRow>
                    ))}
                    <FieldRow label="Dead-letter queue">
                      <span className="tabular">{data.pipeline.dlqDepth}</span>{" "}
                      <span className="text-muted-foreground">in {data.pipeline.dlqTopic}</span>
                    </FieldRow>
                  </dl>
                </Panel>
              </div>

              <Panel
                title="Recent alerts"
                description="Newest synthetic alerts awaiting or under review."
                actions={
                  <Link to="/alerts" className="text-xs underline underline-offset-4">
                    Open full queue
                  </Link>
                }
                bodyClassName="overflow-x-auto"
              >
                <Table>
                  <caption className="sr-only">
                    The eight most recently created synthetic alerts
                  </caption>
                  <TableHeader>
                    <TableRow>
                      <TableHead scope="col">Alert</TableHead>
                      <TableHead scope="col">Priority</TableHead>
                      <TableHead scope="col">Status</TableHead>
                      <TableHead scope="col">Risk band</TableHead>
                      <TableHead scope="col" className="text-right">
                        Final score
                      </TableHead>
                      <TableHead scope="col">Summary</TableHead>
                      <TableHead scope="col">Age</TableHead>
                      <TableHead scope="col">Assignment</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {data.recentAlerts.map((alert) => (
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
                        <TableCell className="max-w-md text-xs">{alert.summary}</TableCell>
                        <TableCell className="tabular">{formatAgeSince(alert.createdAt)}</TableCell>
                        <TableCell className="text-xs">
                          {alert.assigneeId ? "Assigned" : "Unassigned"}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </Panel>
            </div>
          );
        }}
      </QueryState>
    </AppShell>
  );
}
