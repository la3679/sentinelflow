import { createFileRoute, Link } from "@tanstack/react-router";
import { useState } from "react";

import { AppShell } from "@/components/app-shell";
import { AlertStatusChip, PriorityChip, RiskBandChip } from "@/components/chips";
import { QueryState } from "@/components/data-state";
import { PageHeader, Panel } from "@/components/panel";
import { PaginationBar } from "@/components/pagination-bar";
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
import { ALERT_PRIORITY_LABELS, ALERT_STATUS_LABELS, formatAgeSince } from "@/domain/labels";
import {
  ALERT_PRIORITIES,
  ALERT_STATUSES,
  type AlertPriority,
  type AlertStatus,
} from "@/domain/types";
import { useListAlertsQuery } from "@/api/sentinelApi";

export const Route = createFileRoute("/alerts/")({
  head: () => ({
    meta: [
      { title: "Alert queue — SentinelFlow" },
      {
        name: "description",
        content:
          "Filterable, paginated queue of synthetic fraud alerts with priority, status, final score, age and assignment.",
      },
      { property: "og:title", content: "Alert queue — SentinelFlow" },
      {
        property: "og:description",
        content: "Work a synthetic fraud alert queue by priority and status.",
      },
    ],
  }),
  component: AlertQueuePage,
});

const PAGE_SIZE = 20;

/** The sentinel the filter selects use for "do not filter", which is not a value the API takes. */
const ANY = "ANY";

function AlertQueuePage() {
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<AlertStatus | typeof ANY>(ANY);
  const [priority, setPriority] = useState<AlertPriority | typeof ANY>(ANY);

  const query = useListAlertsQuery({
    page,
    size: PAGE_SIZE,
    ...(status === ANY ? {} : { status }),
    ...(priority === ANY ? {} : { priority }),
  });

  return (
    <AppShell>
      <PageHeader
        title="Alert queue"
        description="Open work before closed, then by priority, then oldest first — the queue's own order, which the server decides."
      />

      <Panel
        title="Filters"
        bodyClassName="grid gap-4 p-4 md:grid-cols-2"
        headingLevel="h2"
        className="mb-4"
      >
        <div className="space-y-2">
          <Label htmlFor="alert-status">Status</Label>
          <Select
            value={status}
            onValueChange={(value) => {
              setStatus(value as AlertStatus | typeof ANY);
              setPage(0);
            }}
          >
            <SelectTrigger id="alert-status" className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={ANY}>All statuses</SelectItem>
              {ALERT_STATUSES.map((item) => (
                <SelectItem key={item} value={item}>
                  {ALERT_STATUS_LABELS[item]}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="space-y-2">
          <Label htmlFor="alert-priority">Priority</Label>
          <Select
            value={priority}
            onValueChange={(value) => {
              setPriority(value as AlertPriority | typeof ANY);
              setPage(0);
            }}
          >
            <SelectTrigger id="alert-priority" className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={ANY}>All priorities</SelectItem>
              {ALERT_PRIORITIES.map((item) => (
                <SelectItem key={item} value={item}>
                  {ALERT_PRIORITY_LABELS[item]}
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
          isEmpty={(data) => data.content.length === 0}
          emptyTitle="No alerts match these filters"
          emptyHint="Widen the status and priority filters, or seed the stack so there are alerts to work."
        >
          {(data) => (
            <>
              <div className="overflow-x-auto">
                <Table>
                  <caption className="sr-only">
                    Synthetic alert queue, page {data.page.page + 1} of{" "}
                    {Math.max(1, data.page.totalPages)}
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
                      <TableHead scope="col">Summary</TableHead>
                      <TableHead scope="col">Assignment</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {data.content.map((alert) => (
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
                        {/*
                          The API sends an assignee's identifier and nothing
                          resolves it to a name, so this column says whether the
                          alert is held rather than by whom. A UUID in a queue
                          row is not a person anybody recognises.
                        */}
                        <TableCell className="text-xs">
                          {alert.assigneeId ? "Assigned" : "Unassigned"}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>
              <PaginationBar meta={data.page} onPageChange={setPage} itemNoun="alerts" />
            </>
          )}
        </QueryState>
      </Panel>
    </AppShell>
  );
}
