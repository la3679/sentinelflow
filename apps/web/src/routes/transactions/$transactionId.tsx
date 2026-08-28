import { createFileRoute, Link } from "@tanstack/react-router";

import { AppShell } from "@/components/app-shell";
import { RiskBandChip } from "@/components/chips";
import { EmptyBlock, errorMessage, QueryState } from "@/components/data-state";
import { FieldRow, PageHeader, Panel } from "@/components/panel";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { formatDateTime, formatMoney, PROCESSING_STATUS_LABELS } from "@/domain/labels";
import type { Transaction } from "@/domain/types";
import {
  useGetTransactionAssessmentQuery,
  useGetTransactionQuery,
  useListTransactionsQuery,
} from "@/api/sentinelApi";
import type { SentinelError } from "@/api/transport";

export const Route = createFileRoute("/transactions/$transactionId")({
  head: () => ({
    meta: [
      { title: `Transaction — SentinelFlow` },
      {
        name: "description",
        content:
          "Detail view for a synthetic transaction: its fields, the assessment behind it, and other activity on the same account.",
      },
      { property: "og:title", content: `Transaction — SentinelFlow` },
      {
        property: "og:description",
        content: "Fields, assessment and account context for a synthetic transaction.",
      },
    ],
  }),
  component: TransactionDetailPage,
});

/** How many other transactions on the account to show. Bounded, like every list here. */
const RELATED_SIZE = 10;

function TransactionDetailPage() {
  const { transactionId } = Route.useParams();
  const query = useGetTransactionQuery(transactionId);

  return (
    <AppShell>
      <PageHeader
        title={query.data ? `Transaction ${query.data.transactionReference}` : "Transaction"}
        description="Synthetic transaction record, with the decision behind it and its account context."
        actions={
          <Link to="/transactions/live" className="text-sm underline underline-offset-4">
            Back to the feed
          </Link>
        }
      />
      <QueryState
        isLoading={query.isLoading}
        isError={query.isError}
        error={query.error}
        data={query.data}
        onRetry={() => void query.refetch()}
        loadingLabel="Loading the transaction"
        loadingRows={8}
      >
        {(transaction) => <TransactionDetail transaction={transaction} />}
      </QueryState>
    </AppShell>
  );
}

function TransactionDetail({ transaction }: { transaction: Transaction }) {
  const assessment = useGetTransactionAssessmentQuery(transaction.transactionId);
  const related = useListTransactionsQuery({
    page: 0,
    size: RELATED_SIZE,
    accountReference: transaction.accountReference,
  });

  // A transaction with no assessment is a normal state rather than a failure -
  // ingestion answers before scoring runs - so the 404 is rendered as a
  // sentence about the pipeline, and anything else as an error with a retry.
  const notScoredYet = isNotFound(assessment.error);

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center gap-2">
        {transaction.riskBand ? (
          <RiskBandChip band={transaction.riskBand} />
        ) : (
          <span className="text-xs text-muted-foreground">Not scored yet</span>
        )}
        <span className="text-xs text-muted-foreground">
          {PROCESSING_STATUS_LABELS[transaction.processingStatus]} · occurred{" "}
          {formatDateTime(transaction.occurredAt)}
        </span>
      </div>

      <div className="grid gap-4 xl:grid-cols-2">
        <Panel title="Transaction" bodyClassName="p-0">
          <dl>
            <FieldRow label="Reference">
              <span className="tabular">{transaction.transactionReference}</span>
            </FieldRow>
            <FieldRow label="Account">
              <span className="tabular">{transaction.accountReference}</span>
            </FieldRow>
            <FieldRow label="Merchant">
              <span className="tabular">{transaction.merchantReference}</span>
            </FieldRow>
            {transaction.merchantCategoryCode ? (
              <FieldRow label="Category code">
                <span className="tabular">{transaction.merchantCategoryCode}</span>
              </FieldRow>
            ) : null}
            {transaction.type ? (
              <FieldRow label="Type">{transaction.type.replaceAll("_", " ")}</FieldRow>
            ) : null}
            {transaction.channel ? (
              <FieldRow label="Channel">{transaction.channel.replaceAll("_", " ")}</FieldRow>
            ) : null}
            <FieldRow label="Amount">
              <span className="tabular">
                {formatMoney(transaction.amount.value, transaction.amount.currency)}
              </span>
            </FieldRow>
            {transaction.originCountry ? (
              <FieldRow label="Origin country">{transaction.originCountry}</FieldRow>
            ) : null}
            <FieldRow label="Occurred at">
              <span className="tabular text-xs">{formatDateTime(transaction.occurredAt)}</span>
            </FieldRow>
            <FieldRow label="Ingested at">
              <span className="tabular text-xs">{formatDateTime(transaction.ingestedAt)}</span>
            </FieldRow>
            <FieldRow label="Identifier">
              <span className="tabular text-xs">{transaction.transactionId}</span>
            </FieldRow>
          </dl>
        </Panel>

        <Panel
          title="The decision behind it"
          description="Every version that contributed, because a score is only defensible if what produced it can be named."
          bodyClassName="p-0"
        >
          {notScoredYet ? (
            <EmptyBlock
              title="Not scored yet"
              hint={
                transaction.processingStatus === "FAILED"
                  ? "This transaction's event was dead-lettered, so no assessment is coming. The runbooks cover recovering one."
                  : "Ingestion answers before scoring runs, so an assessment usually appears within seconds. Reload to check again."
              }
            />
          ) : (
            <QueryState
              isLoading={assessment.isLoading}
              isError={assessment.isError}
              error={assessment.error}
              data={assessment.data}
              onRetry={() => void assessment.refetch()}
              loadingLabel="Loading the assessment"
              loadingRows={6}
            >
              {(scored) => (
                <dl>
                  <FieldRow label="Rule score">
                    <span className="tabular">{scored.ruleScore}</span>
                  </FieldRow>
                  <FieldRow label="Model score">
                    <span className="tabular">
                      {scored.modelScore === null ? "—" : scored.modelScore}
                    </span>
                  </FieldRow>
                  <FieldRow label="Final score">
                    <span className="tabular">{scored.finalScore}</span>
                  </FieldRow>
                  <FieldRow label="Degraded">
                    {scored.degraded
                      ? "Yes — scoring was unavailable and the rules alone decided this."
                      : "No"}
                  </FieldRow>
                  <FieldRow label="Model version">
                    <span className="tabular">{scored.modelVersion ?? "—"}</span>
                  </FieldRow>
                  <FieldRow label="Feature version">
                    <span className="tabular">{scored.featureVersion ?? "—"}</span>
                  </FieldRow>
                  <FieldRow label="Ruleset version">
                    <span className="tabular">{scored.rulesetVersion}</span>
                  </FieldRow>
                  <FieldRow label="Policy version">
                    <span className="tabular">{scored.policyVersion}</span>
                  </FieldRow>
                  <FieldRow label="Assessed at">
                    <span className="tabular text-xs">{formatDateTime(scored.assessedAt)}</span>
                  </FieldRow>
                </dl>
              )}
            </QueryState>
          )}
        </Panel>
      </div>

      {notScoredYet ? null : (
        <QueryState
          isLoading={assessment.isLoading}
          isError={assessment.isError}
          error={assessment.error}
          data={assessment.data}
          onRetry={() => void assessment.refetch()}
          loadingLabel="Loading the reason codes"
          loadingRows={3}
        >
          {(scored) => (
            <Panel
              title="Reason codes"
              description="A rule contribution is on the 0-to-100 scale and the rule reasons sum to the rule score. A model contribution is the estimator's own decomposition: it sums to nothing and is comparable only within one model version."
              bodyClassName="overflow-x-auto"
            >
              <Table>
                <caption className="sr-only">Reason codes for this assessment</caption>
                <TableHeader>
                  <TableRow>
                    <TableHead scope="col">Code</TableHead>
                    <TableHead scope="col">Reason</TableHead>
                    <TableHead scope="col">Source</TableHead>
                    <TableHead scope="col" className="text-right">
                      Contribution
                    </TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {scored.reasonCodes.map((reason) => (
                    <TableRow key={reason.code}>
                      <TableCell className="tabular">{reason.code}</TableCell>
                      <TableCell className="text-xs">{reason.description}</TableCell>
                      <TableCell className="text-xs">{reason.source}</TableCell>
                      <TableCell className="tabular text-right">{reason.contribution}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </Panel>
          )}
        </QueryState>
      )}

      <Panel
        title="Other activity on this account"
        description={`The most recent ${RELATED_SIZE} transactions on ${transaction.accountReference}.`}
        bodyClassName="overflow-x-auto"
      >
        <QueryState
          isLoading={related.isLoading}
          isError={related.isError}
          error={related.error}
          data={related.data}
          onRetry={() => void related.refetch()}
          loadingLabel="Loading other activity on this account"
          loadingRows={4}
          isEmpty={(page) =>
            page.content.filter((row) => row.transactionId !== transaction.transactionId).length ===
            0
          }
          emptyTitle="No other activity on this account"
          emptyHint="This synthetic account has a single transaction in the current dataset."
        >
          {(page) => (
            <Table>
              <caption className="sr-only">Other transactions on this account</caption>
              <TableHeader>
                <TableRow>
                  <TableHead scope="col">Transaction</TableHead>
                  <TableHead scope="col">Occurred at</TableHead>
                  <TableHead scope="col">Merchant</TableHead>
                  <TableHead scope="col">Amount</TableHead>
                  <TableHead scope="col">Risk band</TableHead>
                  <TableHead scope="col">Processing</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {page.content
                  .filter((row) => row.transactionId !== transaction.transactionId)
                  .map((row) => (
                    <TableRow key={row.transactionId}>
                      <TableCell>
                        <Link
                          to="/transactions/$transactionId"
                          params={{ transactionId: row.transactionId }}
                          className="tabular underline underline-offset-4"
                        >
                          {row.transactionReference}
                        </Link>
                      </TableCell>
                      <TableCell className="tabular text-xs">
                        {formatDateTime(row.occurredAt)}
                      </TableCell>
                      <TableCell className="tabular">{row.merchantReference}</TableCell>
                      <TableCell className="tabular">
                        {formatMoney(row.amount.value, row.amount.currency)}
                      </TableCell>
                      <TableCell>
                        {row.riskBand ? (
                          <RiskBandChip band={row.riskBand} />
                        ) : (
                          <span className="text-xs text-muted-foreground">Not scored yet</span>
                        )}
                      </TableCell>
                      <TableCell className="text-xs">
                        {PROCESSING_STATUS_LABELS[row.processingStatus]}
                      </TableCell>
                    </TableRow>
                  ))}
              </TableBody>
            </Table>
          )}
        </QueryState>
      </Panel>

      {/*
        No link to the alert this transaction opened, deliberately. The alert
        queue filters on status, priority and assignee, and nothing resolves a
        transaction to its alert - so a control here could only guess. Recorded
        in docs/frontend/API_MIGRATION_AUDIT.md rather than faked; the route
        that exists today is alert to transaction, which the alert page has.
      */}
      <p className="text-xs text-muted-foreground">
        An alert opened from this transaction is reachable from the queue, not from here: the API
        has no lookup from a transaction to its alert. {errorMessageIfUnexpected(assessment.error)}
      </p>
    </div>
  );
}

function isNotFound(error: unknown): boolean {
  return typeof error === "object" && error !== null && (error as SentinelError).status === 404;
}

/** Says what went wrong only when it was not the ordinary "not scored yet". */
function errorMessageIfUnexpected(error: unknown): string {
  return error !== undefined && !isNotFound(error) ? errorMessage(error) : "";
}
