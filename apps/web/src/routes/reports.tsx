import { createFileRoute } from "@tanstack/react-router";
import { Download } from "lucide-react";
import { useMemo, useState } from "react";
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";

import { AppShell } from "@/components/app-shell";
import { ChartFrame } from "@/components/chart-frame";
import { errorMessage, QueryState } from "@/components/data-state";
import { FieldRow, PageHeader, Panel, StatTile } from "@/components/panel";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  ALERT_PRIORITY_LABELS,
  ALERT_STATUS_LABELS,
  formatDateTime,
  RISK_BAND_LABELS,
} from "@/domain/labels";
import {
  ALERT_PRIORITIES,
  ALERT_STATUSES,
  RISK_BANDS,
  type AlertSummaryReport,
} from "@/domain/types";
import { useExportAlertsMutation, useGetAlertSummaryQuery } from "@/api/sentinelApi";

export const Route = createFileRoute("/reports")({
  head: () => ({
    meta: [
      { title: "Reports — SentinelFlow" },
      {
        name: "description",
        content:
          "Counts of synthetic alerts by status, priority and risk band over a chosen window, with a CSV export of the same window.",
      },
      { property: "og:title", content: "Reports — SentinelFlow" },
      {
        property: "og:description",
        content: "Alert counts by status, priority and risk band over a chosen window.",
      },
    ],
  }),
  component: ReportsPage,
});

const WINDOWS = [
  { value: "24h", label: "Last 24 hours", hours: 24 },
  { value: "7d", label: "Last 7 days", hours: 24 * 7 },
  { value: "30d", label: "Last 30 days", hours: 24 * 30 },
] as const;

type WindowValue = (typeof WINDOWS)[number]["value"];

/**
 * Saves the export the API produced.
 *
 * The API names the file in its `Content-Disposition`, which a blob download
 * cannot read, so the name is rebuilt here from the same window that was asked
 * for. It says what it covers rather than being called `export.csv`.
 */
function save(blob: Blob, from: string, to: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = `sentinelflow-alerts-${from.slice(0, 10)}-to-${to.slice(0, 10)}.csv`;
  link.click();
  URL.revokeObjectURL(url);
}

function ReportsPage() {
  const [selected, setSelected] = useState<WindowValue>("7d");

  // Recomputed only when the window changes, not on every render: a `from` that
  // moved with the clock would make every render a different query key and
  // refetch the report continuously.
  const { from, to } = useMemo(() => {
    const hours = WINDOWS.find((w) => w.value === selected)?.hours ?? 24;
    const end = new Date();
    const start = new Date(end.getTime() - hours * 3_600_000);
    return { from: start.toISOString(), to: end.toISOString() };
  }, [selected]);

  const query = useGetAlertSummaryQuery({ from, to });
  const [exportAlerts, exportState] = useExportAlertsMutation();

  const download = (): void => {
    void exportAlerts({ from, to })
      .unwrap()
      .then((blob) => save(blob, from, to))
      .catch(() => {
        // Rendered from the mutation's error state below. Swallowing it here
        // would leave an unhandled rejection saying the same thing twice.
      });
  };

  return (
    <AppShell>
      <PageHeader
        title="Reports"
        description="Counted, never estimated: nothing here is sampled or cached, because a figure in an operations report that is quietly approximate is one somebody quotes."
      />

      <Panel title="Window" className="mb-4" bodyClassName="grid gap-4 p-4 md:grid-cols-2">
        <div className="space-y-2">
          <Label htmlFor="report-window">Period</Label>
          <Select value={selected} onValueChange={(value) => setSelected(value as WindowValue)}>
            <SelectTrigger id="report-window" className="w-full max-w-xs">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {WINDOWS.map((window) => (
                <SelectItem key={window.value} value={window.value}>
                  {window.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <p className="tabular text-xs text-muted-foreground">
            {formatDateTime(from)} to {formatDateTime(to)}
          </p>
        </div>
        <div className="space-y-2">
          <p className="text-sm font-medium">Export</p>
          <Button
            type="button"
            variant="outline"
            onClick={download}
            disabled={exportState.isLoading}
          >
            <Download aria-hidden="true" className="size-4" />
            {exportState.isLoading ? "Preparing…" : "Download this window as CSV"}
          </Button>
          <p className="text-xs text-muted-foreground">
            The alerts themselves, not the counts. Capped at 10,000 rows and refused above it: a
            report somebody opens in a spreadsheet is a file rather than a cursor.
          </p>
          {exportState.isError ? (
            <p role="alert" className="text-xs text-destructive">
              {errorMessage(exportState.error)}
            </p>
          ) : null}
        </div>
      </Panel>

      <QueryState
        isLoading={query.isLoading}
        isError={query.isError}
        error={query.error}
        data={query.data}
        onRetry={() => void query.refetch()}
        loadingLabel="Counting the window"
        loadingRows={6}
      >
        {(data) => <Summary data={data} />}
      </QueryState>
    </AppShell>
  );
}

function Summary({ data }: { data: AlertSummaryReport }) {
  const bands = RISK_BANDS.map((riskBand) => ({
    riskBand,
    label: RISK_BAND_LABELS[riskBand],
    count: data.byRiskBand[riskBand],
  }));

  return (
    <div className="space-y-6">
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
        <StatTile label="Alerts raised" value={String(data.total)} hint="In this window" />
        <StatTile
          label="Still open"
          value={String(data.open)}
          hint="Derived from whether the alert has been closed, not from a list of statuses"
          emphasis={data.open > 0}
        />
        <StatTile label="Closed" value={String(data.closed)} hint="Disposition recorded" />
      </div>

      <div className="grid gap-4 xl:grid-cols-2">
        <Panel
          title="By risk band"
          description="Every band, including the ones with none. A gap where CRITICAL should be reads as missing data rather than as none."
          bodyClassName="p-4"
        >
          <ChartFrame label="Alert count per risk band over the selected window">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={bands} margin={{ left: -12, right: 8, top: 8 }}>
                <CartesianGrid stroke="var(--color-border)" vertical={false} />
                <XAxis dataKey="riskBand" stroke="var(--color-muted-foreground)" fontSize={11} />
                <YAxis stroke="var(--color-muted-foreground)" fontSize={11} allowDecimals={false} />
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
            {bands.map((band) => (
              <li key={band.riskBand} className="tabular">
                {band.label}: {band.count}
              </li>
            ))}
          </ul>
        </Panel>

        <Panel title="By status" bodyClassName="p-0">
          <dl>
            {ALERT_STATUSES.map((status) => (
              <FieldRow key={status} label={ALERT_STATUS_LABELS[status]}>
                <span className="tabular">{data.byStatus[status]}</span>
              </FieldRow>
            ))}
          </dl>
        </Panel>
      </div>

      <div className="grid gap-4 xl:grid-cols-2">
        <Panel title="By priority" bodyClassName="p-0">
          <dl>
            {ALERT_PRIORITIES.map((priority) => (
              <FieldRow key={priority} label={ALERT_PRIORITY_LABELS[priority]}>
                <span className="tabular">{data.byPriority[priority]}</span>
              </FieldRow>
            ))}
          </dl>
        </Panel>

        <Panel title="How to read these figures" bodyClassName="space-y-3 p-4 text-sm">
          <p>
            Every count is over a half-open window — an alert raised exactly on the boundary belongs
            to the window that starts on it, and to no other — so two adjacent periods neither
            overlap nor lose a row between them.
          </p>
          <p>
            The data is a fixed synthetic dataset generated for demonstration. It does not describe
            the behaviour of any real payment portfolio, and no claim is made about detection
            accuracy, false-positive rates or operational savings.
          </p>
          {/*
            A daily trend and a feedback-outcome breakdown used to be shown here
            and neither had an endpoint behind it. They are not in this build
            rather than being drawn from numbers nobody counted.
          */}
          <p className="text-muted-foreground">
            A trend over time and a breakdown of analyst verdicts are not shown: no endpoint counts
            either, and inventing the shape here would put a report in front of somebody that
            nothing had measured.
          </p>
        </Panel>
      </div>
    </div>
  );
}
