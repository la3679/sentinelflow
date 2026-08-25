import { createFileRoute } from "@tanstack/react-router";
import { Download } from "lucide-react";
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
import { QueryState } from "@/components/data-state";
import { FieldRow, PageHeader, Panel } from "@/components/panel";
import { Button } from "@/components/ui/button";
import { RISK_BAND_LABELS } from "@/domain/labels";
import type { ReportsSnapshot } from "@/domain/types";
import { useGetReportsQuery } from "@/api/sentinelApi";

export const Route = createFileRoute("/reports")({
  head: () => ({
    meta: [
      { title: "Reports — SentinelFlow" },
      {
        name: "description",
        content:
          "Alert trend, risk-band distribution and analyst feedback summaries for the SentinelFlow synthetic dataset, with CSV export.",
      },
      { property: "og:title", content: "Reports — SentinelFlow" },
      {
        property: "og:description",
        content: "Trend, risk-band and analyst feedback reporting over synthetic alert data.",
      },
    ],
  }),
  component: ReportsPage,
});

function toCsv(data: ReportsSnapshot): string {
  const rows: string[][] = [["date", "alerts", "escalated"]];
  for (const point of data.dailyAlertTrend) {
    rows.push([point.date, String(point.alerts), String(point.escalated)]);
  }
  return rows.map((row) => row.join(",")).join("\n");
}

function downloadCsv(data: ReportsSnapshot): void {
  const blob = new Blob([toCsv(data)], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = "sentinelflow-synthetic-alert-trend.csv";
  link.click();
  URL.revokeObjectURL(url);
}

function ReportsPage() {
  const query = useGetReportsQuery();

  return (
    <AppShell>
      <PageHeader
        title="Reports"
        description="Aggregated views over the synthetic alert history. Figures describe demonstration data only."
      />
      <QueryState
        isLoading={query.isLoading}
        isError={query.isError}
        error={query.error}
        data={query.data}
        onRetry={() => void query.refetch()}
        loadingLabel="Loading reports"
        loadingRows={8}
      >
        {(data) => (
          <div className="space-y-6">
            <div className="grid gap-4 xl:grid-cols-3">
              <Panel
                title="Alert trend"
                description={data.windowLabel}
                className="xl:col-span-2"
                bodyClassName="p-4"
                actions={
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => downloadCsv(data)}
                  >
                    <Download aria-hidden="true" className="size-4" />
                    Export CSV
                  </Button>
                }
              >
                <ChartFrame label="Daily alerts opened and escalated over the reporting window">
                  <ResponsiveContainer width="100%" height="100%">
                    <LineChart data={data.dailyAlertTrend} margin={{ left: -12, right: 8, top: 8 }}>
                      <CartesianGrid stroke="var(--color-border)" vertical={false} />
                      <XAxis
                        dataKey="date"
                        stroke="var(--color-muted-foreground)"
                        fontSize={11}
                        tickFormatter={(value: string) => value.slice(5)}
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
                      <Line
                        type="monotone"
                        dataKey="alerts"
                        name="Alerts opened"
                        stroke="var(--color-chart-1)"
                        dot={false}
                        strokeWidth={2}
                      />
                      <Line
                        type="monotone"
                        dataKey="escalated"
                        name="Escalated"
                        stroke="var(--color-chart-4)"
                        dot={false}
                        strokeWidth={2}
                      />
                    </LineChart>
                  </ResponsiveContainer>
                </ChartFrame>
              </Panel>

              <Panel title="Risk-band distribution" bodyClassName="p-4">
                <ChartFrame label="Alert count per risk band">
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
                      <Bar dataKey="count" name="Alerts" fill="var(--color-chart-2)" />
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

            <div className="grid gap-4 xl:grid-cols-2">
              <Panel
                title="Analyst feedback summary"
                description="Recorded review outcomes across the synthetic alert history."
                bodyClassName="p-0"
              >
                <dl>
                  {data.feedback.map((item) => (
                    <FieldRow key={item.outcome} label={item.outcome}>
                      <span className="tabular">{item.count}</span>
                    </FieldRow>
                  ))}
                </dl>
              </Panel>

              <Panel title="How to read these figures" bodyClassName="space-y-3 p-4 text-sm">
                <p>
                  Counts come from a fixed synthetic dataset generated for demonstration. They do
                  not describe the behaviour of any real payment portfolio.
                </p>
                <p>
                  No claim is made about detection accuracy, false-positive rates or operational
                  savings. Review outcomes are illustrative analyst decisions only.
                </p>
                <p className="text-muted-foreground">
                  CSV export downloads the daily alert trend shown above.
                </p>
              </Panel>
            </div>
          </div>
        )}
      </QueryState>
    </AppShell>
  );
}
