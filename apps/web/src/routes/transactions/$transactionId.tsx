import { createFileRoute, Link } from "@tanstack/react-router";

import { AppShell } from "@/components/app-shell";
import { RiskBandChip } from "@/components/chips";
import { EmptyBlock, QueryState } from "@/components/data-state";
import { FieldRow, PageHeader, Panel } from "@/components/panel";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { formatDateTime, formatMoney } from "@/domain/labels";
import { useGetTransactionQuery } from "@/api/sentinelApi";

export const Route = createFileRoute("/transactions/$transactionId")({
  head: ({ params }) => ({
    meta: [
      { title: `Transaction ${params.transactionId} — SentinelFlow` },
      {
        name: "description",
        content: `Detail view for synthetic transaction ${params.transactionId}: fields, related account activity, assessment metadata and trace reference.`,
      },
      { property: "og:title", content: `Transaction ${params.transactionId} — SentinelFlow` },
      {
        property: "og:description",
        content: "Fields, related activity and assessment metadata for a synthetic transaction.",
      },
    ],
  }),
  component: TransactionDetailPage,
});

function TransactionDetailPage() {
  const { transactionId } = Route.useParams();
  const query = useGetTransactionQuery(transactionId);

  return (
    <AppShell>
      <PageHeader
        title={`Transaction ${transactionId}`}
        description="Synthetic transaction record with its risk assessment and account context."
        actions={
          <Link to="/transactions/live" className="text-sm underline underline-offset-4">
            Back to live feed
          </Link>
        }
      />
      <QueryState
        isLoading={query.isLoading}
        isError={query.isError}
        error={query.error}
        data={query.data}
        onRetry={() => void query.refetch()}
        loadingLabel={`Loading transaction ${transactionId}`}
        loadingRows={8}
      >
        {({ transaction, relatedActivity, linkedAlertId }) => (
          <div className="space-y-6">
            <div className="flex flex-wrap items-center gap-2">
              <RiskBandChip band={transaction.assessment.riskBand} />
              <span className="tabular text-xs text-muted-foreground">
                Final score {transaction.assessment.finalScore} · authorisation {transaction.status}
              </span>
              {linkedAlertId ? (
                <Link
                  to="/alerts/$alertId"
                  params={{ alertId: linkedAlertId }}
                  className="tabular text-xs underline underline-offset-4"
                >
                  View linked alert {linkedAlertId}
                </Link>
              ) : (
                <span className="text-xs text-muted-foreground">No alert opened</span>
              )}
            </div>

            <div className="grid gap-4 xl:grid-cols-3">
              <Panel title="Transaction fields" bodyClassName="p-0">
                <dl>
                  <FieldRow label="Transaction ID">
                    <span className="tabular">{transaction.transactionId}</span>
                  </FieldRow>
                  <FieldRow label="Account">
                    <span className="tabular">{transaction.accountId}</span>
                  </FieldRow>
                  <FieldRow label="Merchant">
                    <span className="tabular">{transaction.merchantId}</span>
                  </FieldRow>
                  <FieldRow label="Category">{transaction.merchantCategory}</FieldRow>
                  <FieldRow label="Channel">{transaction.channel.replaceAll("_", " ")}</FieldRow>
                  <FieldRow label="Amount">
                    <span className="tabular">
                      {formatMoney(transaction.money.amount, transaction.money.currency)}
                    </span>
                  </FieldRow>
                  <FieldRow label="Country">{transaction.countryCode}</FieldRow>
                  <FieldRow label="Device">
                    <span className="tabular">{transaction.deviceId}</span>
                  </FieldRow>
                  <FieldRow label="Occurred at">
                    <span className="tabular text-xs">
                      {formatDateTime(transaction.occurredAt)}
                    </span>
                  </FieldRow>
                </dl>
              </Panel>

              <Panel title="Assessment & model metadata" bodyClassName="p-0">
                <dl>
                  <FieldRow label="Rule score">
                    <span className="tabular">{transaction.assessment.ruleScore}</span>
                  </FieldRow>
                  <FieldRow label="Model score">
                    <span className="tabular">{transaction.assessment.modelScore}</span>
                  </FieldRow>
                  <FieldRow label="Final score">
                    <span className="tabular">{transaction.assessment.finalScore}</span>
                  </FieldRow>
                  <FieldRow label="Model version">
                    <span className="tabular">{transaction.assessment.modelVersion}</span>
                  </FieldRow>
                  <FieldRow label="Feature version">
                    <span className="tabular">{transaction.assessment.featureVersion}</span>
                  </FieldRow>
                  <FieldRow label="Policy version">
                    <span className="tabular">{transaction.assessment.policyVersion}</span>
                  </FieldRow>
                  <FieldRow label="Scoring latency">
                    <span className="tabular">{transaction.assessment.scoringLatencyMs} ms</span>
                  </FieldRow>
                </dl>
              </Panel>

              <Panel title="Correlation & trace" bodyClassName="p-0">
                <dl>
                  <FieldRow label="Correlation ID">
                    <span className="tabular text-xs">{transaction.correlationId}</span>
                  </FieldRow>
                  <FieldRow label="Assessment ID">
                    <span className="tabular text-xs">{transaction.assessment.assessmentId}</span>
                  </FieldRow>
                  <FieldRow label="Trace lookup">
                    <span className="text-xs text-muted-foreground">
                      Distributed traces are stored by the external observability stack. This
                      console displays the reference only.
                    </span>
                  </FieldRow>
                </dl>
              </Panel>
            </div>

            <Panel
              title="Reason codes"
              description="Signals recorded on this assessment."
              bodyClassName="overflow-x-auto"
            >
              <Table>
                <caption className="sr-only">Reason codes for this transaction assessment</caption>
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
                  {transaction.assessment.reasonCodes.map((reason) => (
                    <TableRow key={reason.code}>
                      <TableCell className="tabular">{reason.code}</TableCell>
                      <TableCell className="text-xs">{reason.label}</TableCell>
                      <TableCell className="text-xs">{reason.source}</TableCell>
                      <TableCell className="tabular text-right">{reason.contribution}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </Panel>

            <Panel
              title="Related account activity"
              description={`Other synthetic transactions on ${transaction.accountId}.`}
              bodyClassName="overflow-x-auto"
            >
              {relatedActivity.length === 0 ? (
                <EmptyBlock
                  title="No other activity on this account"
                  hint="This synthetic account has a single transaction in the current dataset."
                />
              ) : (
                <Table>
                  <caption className="sr-only">Related activity for this account</caption>
                  <TableHeader>
                    <TableRow>
                      <TableHead scope="col">Transaction</TableHead>
                      <TableHead scope="col">Occurred at</TableHead>
                      <TableHead scope="col">Merchant</TableHead>
                      <TableHead scope="col">Amount</TableHead>
                      <TableHead scope="col">Risk band</TableHead>
                      <TableHead scope="col">Authorisation</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {relatedActivity.map((item) => (
                      <TableRow key={item.transactionId}>
                        <TableCell>
                          <Link
                            to="/transactions/$transactionId"
                            params={{ transactionId: item.transactionId }}
                            className="tabular underline underline-offset-4"
                          >
                            {item.transactionId}
                          </Link>
                        </TableCell>
                        <TableCell className="tabular text-xs">
                          {formatDateTime(item.occurredAt)}
                        </TableCell>
                        <TableCell className="tabular">{item.merchantId}</TableCell>
                        <TableCell className="tabular">
                          {formatMoney(item.money.amount, item.money.currency)}
                        </TableCell>
                        <TableCell>
                          <RiskBandChip band={item.riskBand} />
                        </TableCell>
                        <TableCell className="text-xs">{item.status}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )}
            </Panel>
          </div>
        )}
      </QueryState>
    </AppShell>
  );
}
