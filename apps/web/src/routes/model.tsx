import { createFileRoute } from "@tanstack/react-router";
import { Info, Lock } from "lucide-react";

import { AppShell } from "@/components/app-shell";
import { PriorityChip, RiskBandChip } from "@/components/chips";
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
import type { ModelMetrics } from "@/domain/types";
import { useGetModelPolicyQuery } from "@/api/sentinelApi";

export const Route = createFileRoute("/model")({
  head: () => ({
    meta: [
      { title: "Model & policy — SentinelFlow" },
      {
        name: "description",
        content:
          "Read-only view of the active synthetic model, what it was measured at, the banding and alerting policy that runs, and the limitations of both.",
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

/** A proportion as a percentage, from a number the API sends between 0 and 1. */
function percent(value: number): string {
  return `${(value * 100).toFixed(1)}%`;
}

const METRIC_LABELS: { key: keyof ModelMetrics; label: string; format: (v: number) => string }[] = [
  { key: "precision", label: "Precision", format: percent },
  { key: "recall", label: "Recall", format: percent },
  { key: "f1", label: "F1", format: (v) => v.toFixed(3) },
  { key: "averagePrecision", label: "Average precision (PR-AUC)", format: (v) => v.toFixed(3) },
  { key: "falsePositiveRate", label: "False-positive rate", format: percent },
  { key: "operatingThreshold", label: "The model's own operating point", format: (v) => String(v) },
];

function ModelPage() {
  const query = useGetModelPolicyQuery();

  return (
    <AppShell>
      <PageHeader
        title="Model & policy"
        description="What is scoring, what it was measured at, and what this system does with the score."
      />
      <p className="mb-4 flex items-start gap-2 rounded-md border border-border bg-surface px-3 py-2 text-xs text-muted-foreground">
        <Lock aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
        <span>
          Read-only by design. Promoting a model or changing a threshold safely needs authorization,
          validation, rollback and an audit trail that do not exist here, and a half-built promotion
          control is worse than none.
        </span>
      </p>

      <QueryState
        isLoading={query.isLoading}
        isError={query.isError}
        error={query.error}
        data={query.data}
        onRetry={() => void query.refetch()}
        loadingLabel="Loading the model and the policy"
        loadingRows={6}
      >
        {(data) => (
          <div className="grid gap-4 xl:grid-cols-2">
            <Panel
              title="What is scoring"
              description={
                data.modelAvailable
                  ? "Reported by the scoring service, which is the only thing that can say what it has actually loaded."
                  : undefined
              }
              bodyClassName="p-0"
            >
              {/*
                The model half and the policy half have different owners, and
                either can be missing without the other being wrong. A scoring
                service that is restarting must not blank this screen: the
                thresholds below are exactly what somebody would be looking for
                during a scoring outage.
              */}
              {data.modelAvailable ? (
                <dl>
                  <FieldRow label="Model version">
                    <span className="tabular">{data.modelVersion}</span>
                  </FieldRow>
                  <FieldRow label="Feature version">
                    <span className="tabular">{data.featureVersion}</span>
                  </FieldRow>
                  <FieldRow label="Algorithm">{data.algorithm}</FieldRow>
                  <FieldRow label="Trained at">
                    <span className="tabular text-xs">
                      {data.trainedAt ? formatDateTime(data.trainedAt) : "—"}
                    </span>
                  </FieldRow>
                  <FieldRow label="Artifact checksum">
                    <span className="tabular text-xs break-all">{data.artifactSha256}</span>
                  </FieldRow>
                </dl>
              ) : (
                <div className="p-4 text-sm">
                  <p className="font-medium">The scoring service is not answering.</p>
                  <p className="mt-1 text-muted-foreground">
                    Which model is loaded is its to report, so that half of this screen is
                    unavailable. Assessments continue on the rules alone and are marked degraded;
                    nothing is lost. The policy below is this API&apos;s own and is unaffected.
                  </p>
                </div>
              )}
            </Panel>

            <Panel
              title="What it was measured at"
              description="On its own evaluation split, on synthetic data. Accuracy is deliberately absent: the classes are extremely imbalanced, so it would be close to meaningless and somebody would quote it."
              bodyClassName="p-0"
            >
              {data.metrics ? (
                <dl>
                  {METRIC_LABELS.map(({ key, label, format }) => (
                    <FieldRow key={key} label={label}>
                      <span className="tabular">{format(data.metrics![key])}</span>
                    </FieldRow>
                  ))}
                </dl>
              ) : (
                <p className="p-4 text-sm text-muted-foreground">
                  Unavailable while the scoring service is not answering.
                </p>
              )}
            </Panel>

            <Panel
              title="The policy that runs"
              description={`Policy ${data.policyVersion}. What the band is, what opens an alert, and at what priority.`}
              bodyClassName="overflow-x-auto"
            >
              <Table>
                <caption className="sr-only">
                  Risk bands, the score each starts at, and what happens to a transaction in it
                </caption>
                <TableHeader>
                  <TableRow>
                    <TableHead scope="col">Risk band</TableHead>
                    <TableHead scope="col" className="text-right">
                      From final score
                    </TableHead>
                    <TableHead scope="col">Opens an alert</TableHead>
                    <TableHead scope="col">At priority</TableHead>
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
                      <TableCell className="text-xs">
                        {threshold.raisesAlert ? "Yes" : "No — scored and stored only"}
                      </TableCell>
                      <TableCell>
                        {threshold.priority ? (
                          <PriorityChip priority={threshold.priority} />
                        ) : (
                          <span className="text-xs text-muted-foreground">—</span>
                        )}
                      </TableCell>
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
