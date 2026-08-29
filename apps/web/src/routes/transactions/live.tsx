import { createFileRoute, Link } from "@tanstack/react-router";
import { Pause, Play } from "lucide-react";
import { useState } from "react";

import { AppShell } from "@/components/app-shell";
import { RiskBandChip } from "@/components/chips";
import { QueryState } from "@/components/data-state";
import { PageHeader, Panel } from "@/components/panel";
import { PaginationBar } from "@/components/pagination-bar";
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
import {
  formatDateTime,
  formatMoney,
  PROCESSING_STATUS_LABELS,
  RISK_BAND_LABELS,
} from "@/domain/labels";
import { RISK_BANDS, type RiskBand } from "@/domain/types";
import { refreshWhile } from "@/api/refresh";
import { useListTransactionsQuery } from "@/api/sentinelApi";

export const Route = createFileRoute("/transactions/live")({
  head: () => ({
    meta: [
      { title: "Live transactions — SentinelFlow" },
      {
        name: "description",
        content:
          "The synthetic transaction feed, newest first, refreshed on an interval with risk-band and account filters.",
      },
      { property: "og:title", content: "Live transactions — SentinelFlow" },
      {
        property: "og:description",
        content: "The synthetic transaction feed, newest first.",
      },
    ],
  }),
  component: LiveTransactionsPage,
});

const PAGE_SIZE = 25;

/** How often the feed re-asks. Slow enough to be a feed rather than a load test. */
const REFRESH_MS = 5_000;

const ANY = "ANY";

/** `ACC-000123`. The API refuses anything else, so the console does not send it. */
const ACCOUNT_REFERENCE = /^ACC-\d{6}$/;

function LiveTransactionsPage() {
  const [live, setLive] = useState(true);
  const [page, setPage] = useState(0);
  const [riskBand, setRiskBand] = useState<RiskBand | typeof ANY>(ANY);
  const [account, setAccount] = useState("");

  const accountIsUsable = account === "" || ACCOUNT_REFERENCE.test(account);

  const query = useListTransactionsQuery(
    {
      page,
      size: PAGE_SIZE,
      ...(riskBand === ANY ? {} : { riskBand }),
      ...(accountIsUsable && account !== "" ? { accountReference: account } : {}),
    },
    // Polling is what "live" means here: the API is asked again, rather than a
    // window being advanced over a fixture. Paused rather than throttled when
    // the operator asks, because a feed that keeps moving under a cursor is
    // unreadable while somebody is trying to read a row.
    refreshWhile(live && page === 0, REFRESH_MS),
  );

  return (
    <AppShell>
      <PageHeader
        title="Live transactions"
        description={`The feed, newest first, re-read every ${REFRESH_MS / 1000} seconds while it is running.`}
        actions={
          <Button
            type="button"
            variant={live ? "outline" : "default"}
            onClick={() => setLive((value) => !value)}
            aria-pressed={live}
          >
            {live ? (
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
        {live
          ? page === 0
            ? "Transaction feed is running."
            : "Transaction feed is paused while you are reading a later page."
          : "Transaction feed is paused."}
      </p>

      <Panel title="Filters" className="mb-4" bodyClassName="grid gap-4 p-4 md:grid-cols-2">
        <div className="space-y-2">
          <Label htmlFor="txn-account">Account</Label>
          <Input
            id="txn-account"
            placeholder="ACC-000045"
            value={account}
            aria-invalid={accountIsUsable ? undefined : true}
            aria-describedby={accountIsUsable ? undefined : "txn-account-error"}
            onChange={(event) => {
              setAccount(event.target.value.toUpperCase());
              setPage(0);
            }}
          />
          {/*
            An account reference, not a search box. The API filters on this
            field exactly and has no free-text search, and a box that quietly
            matched nothing would be a control that does not work.
          */}
          {accountIsUsable ? (
            <p className="text-xs text-muted-foreground">
              An exact account reference. Leave it empty for every account.
            </p>
          ) : (
            <p id="txn-account-error" role="alert" className="text-xs text-destructive">
              An account reference looks like ACC-000045.
            </p>
          )}
        </div>
        <div className="space-y-2">
          <Label htmlFor="txn-band">Risk band</Label>
          <Select
            value={riskBand}
            onValueChange={(value) => {
              setRiskBand(value as RiskBand | typeof ANY);
              setPage(0);
            }}
          >
            <SelectTrigger id="txn-band" className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={ANY}>All risk bands</SelectItem>
              {RISK_BANDS.map((band) => (
                <SelectItem key={band} value={band}>
                  {RISK_BAND_LABELS[band]}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <p className="text-xs text-muted-foreground">
            The band of each transaction&apos;s current assessment. A transaction not yet scored has
            none, and is left out when this filter is set.
          </p>
        </div>
      </Panel>

      <Panel title="Feed" bodyClassName="p-0">
        <QueryState
          isLoading={query.isLoading}
          isError={query.isError}
          error={query.error}
          data={query.data}
          onRetry={() => void query.refetch()}
          loadingLabel="Loading the transaction feed"
          loadingRows={10}
          isEmpty={(data) => data.content.length === 0}
          emptyTitle="No transactions match these filters"
          emptyHint="Widen the risk-band filter, clear the account, or seed the stack so there is traffic to read."
        >
          {(data) => (
            <>
              <div className="overflow-x-auto">
                <Table>
                  <caption className="sr-only">
                    Synthetic transactions, newest first, page {data.page.page + 1} of{" "}
                    {Math.max(1, data.page.totalPages)}
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
                      <TableHead scope="col">Processing</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {data.content.map((transaction) => (
                      <TableRow key={transaction.transactionId}>
                        <TableCell>
                          <Link
                            to="/transactions/$transactionId"
                            params={{ transactionId: transaction.transactionId }}
                            className="tabular underline underline-offset-4"
                          >
                            {transaction.transactionReference}
                          </Link>
                        </TableCell>
                        <TableCell className="tabular text-xs">
                          {formatDateTime(transaction.occurredAt)}
                        </TableCell>
                        <TableCell className="tabular">{transaction.accountReference}</TableCell>
                        <TableCell className="tabular">{transaction.merchantReference}</TableCell>
                        <TableCell className="text-xs">
                          {transaction.channel?.replaceAll("_", " ") ?? "—"}
                        </TableCell>
                        <TableCell className="tabular">
                          {formatMoney(transaction.amount.value, transaction.amount.currency)}
                        </TableCell>
                        <TableCell>
                          {/*
                            Null until an assessment exists, which is a normal
                            state rather than missing data: ingestion answers
                            before scoring runs.
                          */}
                          {transaction.riskBand ? (
                            <RiskBandChip band={transaction.riskBand} />
                          ) : (
                            <span className="text-xs text-muted-foreground">Not scored yet</span>
                          )}
                        </TableCell>
                        <TableCell className="text-xs">
                          {PROCESSING_STATUS_LABELS[transaction.processingStatus]}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>
              <PaginationBar meta={data.page} onPageChange={setPage} itemNoun="transactions" />
            </>
          )}
        </QueryState>
      </Panel>
    </AppShell>
  );
}
