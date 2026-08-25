import { createFileRoute } from "@tanstack/react-router";
import { Info, Lock } from "lucide-react";

import { AppShell } from "@/components/app-shell";
import { RiskBandChip } from "@/components/chips";
import { QueryState } from "@/components/data-state";
import { FieldRow, PageHeader, Panel } from "@/components/panel";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { formatDateTime } from "@/domain/labels";
import { useGetModelPolicyQuery } from "@/api/sentinelApi";

export const Route = createFileRoute("/model")({
  head: () => ({
    meta: [
      { title: "Model & policy — SentinelFlow" },
      {
        name: "description",
        content:
          "Read-only view of the active synthetic model version, feature version, threshold policy and documented limitations.",
      },
      { property: "og:title", content: "Model & policy — SentinelFlow" },
      {
        property: "og:description",
        content: "Read-only model version, thresholds and limitations for a synthetic risk model.",
      },
    ],
  }),
  component: ModelPage,
});

function ModelPage() {
  const query = useGetModelPolicyQuery();

  return (
    <AppShell>
      <PageHeader
        title="Model & policy"
        description="Read-only metadata for the active synthetic scoring model and its alerting policy."
      />
      <p className="mb-4 flex items-start gap-2 rounded-md border border-border bg-surface px-3 py-2 text-xs text-muted-foreground">
        <Lock aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
        <span>
          This screen is read-only by design. Model promotion and policy changes are performed in
          the external backend services, never from this console.
        </span>
      </p>

      <QueryState
        isLoading={query.isLoading}
        isError={query.isError}
        error={query.error}
        data={query.data}
        onRetry={() => void query.refetch()}
        loadingLabel="Loading model and policy metadata"
        loadingRows={6}
      >
        {(data) => (
          <div className="grid gap-4 xl:grid-cols-2">
            <Panel title="Active versions" bodyClassName="p-0">
              <dl>
                <FieldRow label="Model version">
                  <span className="tabular">{data.modelVersion}</span>
                </FieldRow>
                <FieldRow label="Feature version">
                  <span className="tabular">{data.featureVersion}</span>
                </FieldRow>
                <FieldRow label="Policy version">
                  <span className="tabular">{data.policyVersion}</span>
                </FieldRow>
                <FieldRow label="Trained at">
                  <span className="tabular text-xs">{formatDateTime(data.trainedAt)}</span>
                </FieldRow>
              </dl>
            </Panel>

            <Panel title="Metrics summary" bodyClassName="p-0">
              <dl>
                {data.metrics.map((metric) => (
                  <FieldRow key={metric.key} label={metric.label}>
                    <span className="tabular">{metric.value}</span>
                  </FieldRow>
                ))}
              </dl>
            </Panel>

            <Panel
              title="Threshold policy"
              description="Score ranges that determine banding and alerting."
              bodyClassName="overflow-x-auto"
            >
              <Table>
                <caption className="sr-only">Risk band thresholds and resulting actions</caption>
                <TableHeader>
                  <TableRow>
                    <TableHead scope="col">Risk band</TableHead>
                    <TableHead scope="col" className="text-right">
                      Minimum final score
                    </TableHead>
                    <TableHead scope="col">Policy action</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {data.thresholds.map((threshold) => (
                    <TableRow key={threshold.riskBand}>
                      <TableCell>
                        <RiskBandChip band={threshold.riskBand} />
                      </TableCell>
                      <TableCell className="tabular text-right">
                        {threshold.minFinalScore}
                      </TableCell>
                      <TableCell className="text-xs">{threshold.action}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </Panel>

            <Panel title="Limitations" bodyClassName="p-4">
              <ul className="space-y-3 text-sm">
                {data.limitations.map((limitation) => (
                  <li key={limitation} className="flex items-start gap-2">
                    <Info aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-primary" />
                    <span>{limitation}</span>
                  </li>
                ))}
              </ul>
            </Panel>
          </div>
        )}
      </QueryState>
    </AppShell>
  );
}
