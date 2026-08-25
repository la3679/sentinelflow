import { createFileRoute, Link } from "@tanstack/react-router";
import { useState } from "react";

import { AppShell } from "@/components/app-shell";
import { AlertStatusChip, PriorityChip, RiskBandChip } from "@/components/chips";
import { QueryState } from "@/components/data-state";
import { PageHeader, Panel } from "@/components/panel";
import { PaginationBar } from "@/components/pagination-bar";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { ALERT_STATUS_LABELS, formatAge, formatMoney, RISK_BAND_LABELS } from "@/domain/labels";
import { ALERT_STATUSES, RISK_BANDS, type AlertStatus, type RiskBand } from "@/domain/types";
import { useListAlertsQuery } from "@/api/sentinelApi";

export const Route = createFileRoute("/alerts/")({
  head: () => ({
    meta: [
      { title: "Alert queue — SentinelFlow" },
      {
        name: "description",
        content:
          "Filterable, paginated queue of synthetic fraud alerts with priority, status, final score, age and assignee.",
      },
      { property: "og:title", content: "Alert queue — SentinelFlow" },
      {
        property: "og:description",
        content: "Work a synthetic fraud alert queue by priority, status and risk band.",
      },
    ],
  }),
  component: AlertQueuePage,
});

const PAGE_SIZE = 20;

function AlertQueuePage() {
  const [page, setPage] = useState(1);
  const [status, setStatus] = useState<AlertStatus | "ALL">("ALL");
  const [riskBand, setRiskBand] = useState<RiskBand | "ALL">("ALL");
  const [search, setSearch] = useState("");

  const query = useListAlertsQuery({ page, pageSize: PAGE_SIZE, status, riskBand, search });

  return (
    <AppShell>
      <PageHeader
        title="Alert queue"
        description="Synthetic alerts ordered by priority, then by final score."
      />

      <Panel
        title="Filters"
        bodyClassName="grid gap-4 p-4 md:grid-cols-3"
        headingLevel="h2"
        className="mb-4"
      >
        <div className="space-y-2">
          <Label htmlFor="alert-search">Search</Label>
          <Input
            id="alert-search"
            placeholder="ALR-000012, ACC-000045, TXN-000101"
            value={search}
            onChange={(event) => {
              setSearch(event.target.value);
              setPage(1);
            }}
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="alert-status">Status</Label>
          <Select
            value={status}
            onValueChange={(value) => {
              setStatus(value as AlertStatus | "ALL");
              setPage(1);
            }}
          >
            <SelectTrigger id="alert-status" className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">All statuses</SelectItem>
              {ALERT_STATUSES.map((item) => (
                <SelectItem key={item} value={item}>
                  {ALERT_STATUS_LABELS[item]}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="space-y-2">
          <Label htmlFor="alert-band">Risk band</Label>
          <Select
            value={riskBand}
            onValueChange={(value) => {
              setRiskBand(value as RiskBand | "ALL");
              setPage(1);
            }}
          >
            <SelectTrigger id="alert-band" className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">All risk bands</SelectItem>
              {RISK_BANDS.map((item) => (
                <SelectItem key={item} value={item}>
                  {RISK_BAND_LABELS[item]}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      </Panel>

      <Panel title="Alerts" bodyClassName="p-0">
        <QueryState
          isLoading={query.isLoading}
          isError={query.isError}
          error={query.error}
          data={query.data}
          onRetry={() => void query.refetch()}
          loadingLabel="Loading alert queue"
          loadingRows={8}
          isEmpty={(data) => data.items.length === 0}
          emptyTitle="No alerts match these filters"
          emptyHint="Clear the search box or widen the status and risk-band filters."
        >
          {(data) => (
            <>
              <div className="overflow-x-auto">
                <Table>
                  <caption className="sr-only">
                    Synthetic alert queue, page {data.page} of {data.totalPages}
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
                      <TableHead scope="col">Age</TableHead>
                      <TableHead scope="col">Top reason</TableHead>
                      <TableHead scope="col">Account</TableHead>
                      <TableHead scope="col">Amount</TableHead>
                      <TableHead scope="col">Assignee</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {data.items.map((alert) => (
                      <TableRow key={alert.alertId}>
                        <TableCell>
                          <Link
                            to="/alerts/$alertId"
                            params={{ alertId: alert.alertId }}
                            className="tabular underline underline-offset-4"
                          >
                            {alert.alertId}
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
                        <TableCell className="tabular">{formatAge(alert.ageMinutes)}</TableCell>
                        <TableCell className="tabular">{alert.topReasonCode}</TableCell>
                        <TableCell className="tabular">{alert.accountId}</TableCell>
                        <TableCell className="tabular">
                          {formatMoney(alert.money.amount, alert.money.currency)}
                        </TableCell>
                        <TableCell className="tabular">{alert.assignee ?? "Unassigned"}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>
              <PaginationBar
                page={data.page}
                totalPages={data.totalPages}
                totalItems={data.totalItems}
                pageSize={data.pageSize}
                onPageChange={setPage}
                itemNoun="alerts"
              />
            </>
          )}
        </QueryState>
      </Panel>
    </AppShell>
  );
}
