import { createFileRoute, Link } from "@tanstack/react-router";
import { Pause, Play } from "lucide-react";
import { useEffect, useState } from "react";

import { AppShell } from "@/components/app-shell";
import { RiskBandChip } from "@/components/chips";
import { QueryState } from "@/components/data-state";
import { PageHeader, Panel } from "@/components/panel";
import { Button } from "@/components/ui/button";
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
import { formatDateTime, formatMoney, RISK_BAND_LABELS } from "@/domain/labels";
import { RISK_BANDS, type RiskBand, type Transaction } from "@/domain/types";
import { useListTransactionsQuery } from "@/api/sentinelApi";

export const Route = createFileRoute("/transactions/live")({
  head: () => ({
    meta: [
      { title: "Live transactions — SentinelFlow" },
      {
        name: "description",
        content:
          "Simulated streaming feed of synthetic scored transactions with pause, resume, filtering and risk-band chips.",
      },
      { property: "og:title", content: "Live transactions — SentinelFlow" },
      {
        property: "og:description",
        content: "Simulated streaming feed of synthetic scored transactions.",
      },
    ],
  }),
  component: LiveTransactionsPage,
});

const WINDOW_SIZE = 25;
const TICK_MS = 2_500;

const TRANSACTION_STATUS_OPTIONS: readonly (Transaction["status"] | "ALL")[] = [
  "ALL",
  "AUTHORIZED",
  "DECLINED",
  "PENDING",
  "REVERSED",
];

function LiveTransactionsPage() {
  const [streaming, setStreaming] = useState(true);
  const [page, setPage] = useState(1);
  const [riskBand, setRiskBand] = useState<RiskBand | "ALL">("ALL");
  const [status, setStatus] = useState<Transaction["status"] | "ALL">("ALL");
  const [search, setSearch] = useState("");

  const query = useListTransactionsQuery({
    page,
    pageSize: WINDOW_SIZE,
    riskBand,
    status,
    search,
  });

  const totalPages = query.data?.totalPages ?? 1;

  // Simulated stream: advances the bounded window while running.
  useEffect(() => {
    if (!streaming) return;
    const timer = window.setInterval(() => {
      setPage((current) => (current >= totalPages ? 1 : current + 1));
    }, TICK_MS);
    return () => window.clearInterval(timer);
  }, [streaming, totalPages]);

  return (
    <AppShell>
      <PageHeader
        title="Live transactions"
        description={`Simulated feed advancing a bounded ${WINDOW_SIZE}-row window every ${TICK_MS / 1000} seconds over the synthetic dataset.`}
        actions={
          <Button
            type="button"
            variant={streaming ? "outline" : "default"}
            onClick={() => setStreaming((value) => !value)}
            aria-pressed={streaming}
          >
            {streaming ? (
              <>
                <Pause aria-hidden="true" className="size-4" />
                Pause feed
              </>
            ) : (
              <>
                <Play aria-hidden="true" className="size-4" />
                Resume feed
              </>
            )}
          </Button>
        }
      />

      <p className="sr-only" role="status" aria-live="polite">
        {streaming ? "Transaction feed is running." : "Transaction feed is paused."}
      </p>

      <Panel title="Filters" className="mb-4" bodyClassName="grid gap-4 p-4 md:grid-cols-3">
        <div className="space-y-2">
          <Label htmlFor="txn-search">Search</Label>
          <Input
            id="txn-search"
            placeholder="TXN-000101, ACC-000045, MER-0042"
            value={search}
            onChange={(event) => {
              setSearch(event.target.value);
              setPage(1);
            }}
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="txn-band">Risk band</Label>
          <Select
            value={riskBand}
            onValueChange={(value) => {
              setRiskBand(value as RiskBand | "ALL");
              setPage(1);
            }}
          >
            <SelectTrigger id="txn-band" className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">All risk bands</SelectItem>
              {RISK_BANDS.map((band) => (
                <SelectItem key={band} value={band}>
                  {RISK_BAND_LABELS[band]}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="space-y-2">
          <Label htmlFor="txn-status">Authorisation status</Label>
          <Select
            value={status}
            onValueChange={(value) => {
              setStatus(value as Transaction["status"] | "ALL");
              setPage(1);
            }}
          >
            <SelectTrigger id="txn-status" className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {TRANSACTION_STATUS_OPTIONS.map((option) => (
                <SelectItem key={option} value={option}>
                  {option === "ALL" ? "All statuses" : option.replace("_", " ")}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      </Panel>

      <Panel
        title="Feed window"
        description={`Window ${page} of ${totalPages}. The feed is bounded to ${WINDOW_SIZE} rows to keep the table responsive.`}
        bodyClassName="p-0"
      >
        <QueryState
          isLoading={query.isLoading}
          isError={query.isError}
          error={query.error}
          data={query.data}
          onRetry={() => void query.refetch()}
          loadingLabel="Loading transaction feed"
          loadingRows={10}
          isEmpty={(data) => data.items.length === 0}
          emptyTitle="No transactions match these filters"
          emptyHint="Widen the risk-band or status filter, or clear the search box."
        >
          {(data) => (
            <div className="overflow-x-auto">
              <Table>
                <caption className="sr-only">
                  Simulated live feed of synthetic scored transactions
                </caption>
                <TableHeader>
                  <TableRow>
                    <TableHead scope="col">Transaction</TableHead>
                    <TableHead scope="col">Occurred at</TableHead>
                    <TableHead scope="col">Account</TableHead>
                    <TableHead scope="col">Merchant</TableHead>
                    <TableHead scope="col">Channel</TableHead>
                    <TableHead scope="col">Amount</TableHead>
                    <TableHead scope="col">Risk band</TableHead>
                    <TableHead scope="col" className="text-right">
                      Final score
                    </TableHead>
                    <TableHead scope="col">Authorisation</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {data.items.map((transaction) => (
                    <TableRow key={transaction.transactionId}>
                      <TableCell>
                        <Link
                          to="/transactions/$transactionId"
                          params={{ transactionId: transaction.transactionId }}
                          className="tabular underline underline-offset-4"
                        >
                          {transaction.transactionId}
                        </Link>
                      </TableCell>
                      <TableCell className="tabular text-xs">
                        {formatDateTime(transaction.occurredAt)}
                      </TableCell>
                      <TableCell className="tabular">{transaction.accountId}</TableCell>
                      <TableCell className="tabular">{transaction.merchantId}</TableCell>
                      <TableCell className="text-xs">
                        {transaction.channel.replaceAll("_", " ")}
                      </TableCell>
                      <TableCell className="tabular">
                        {formatMoney(transaction.money.amount, transaction.money.currency)}
                      </TableCell>
                      <TableCell>
                        <RiskBandChip band={transaction.assessment.riskBand} />
                      </TableCell>
                      <TableCell className="tabular text-right">
                        {transaction.assessment.finalScore}
                      </TableCell>
                      <TableCell className="text-xs">{transaction.status}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
        </QueryState>
      </Panel>
    </AppShell>
  );
}
