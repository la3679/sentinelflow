import { createFileRoute } from "@tanstack/react-router";
import { RotateCcw } from "lucide-react";

import { AppShell } from "@/components/app-shell";
import { HealthChip } from "@/components/chips";
import { QueryState } from "@/components/data-state";
import { PageHeader, Panel } from "@/components/panel";
import { Button } from "@/components/ui/button";
import { formatDateTime } from "@/domain/labels";
import { useGetSystemHealthQuery } from "@/api/sentinelApi";

export const Route = createFileRoute("/health")({
  head: () => ({
    meta: [
      { title: "System health — SentinelFlow" },
      {
        name: "description",
        content:
          "Whether the API, its database and the scoring service are answering, asked live rather than read from cached state.",
      },
      { property: "og:title", content: "System health — SentinelFlow" },
      {
        property: "og:description",
        content: "Whether each part of the stack is answering.",
      },
    ],
  }),
  component: HealthPage,
});

function HealthPage() {
  const query = useGetSystemHealthQuery();

  return (
    <AppShell>
      <PageHeader
        title="System health"
        description="Each component is asked when you load this page, not read from something's cached opinion."
        actions={
          <Button
            type="button"
            variant="outline"
            onClick={() => void query.refetch()}
            disabled={query.isFetching}
          >
            <RotateCcw aria-hidden="true" className="size-4" />
            Check again
          </Button>
        }
      />
      <QueryState
        isLoading={query.isLoading}
        isError={query.isError}
        error={query.error}
        data={query.data}
        onRetry={() => void query.refetch()}
        loadingLabel="Checking the stack"
        loadingRows={4}
      >
        {(data) => (
          <div className="space-y-6">
            <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
              {data.components.map((component) => (
                <section
                  key={component.componentId}
                  className="rounded-lg border border-border bg-card px-4 py-3"
                  aria-labelledby={`health-${component.componentId}`}
                >
                  <h2
                    id={`health-${component.componentId}`}
                    className="text-xs font-medium tracking-wide text-muted-foreground uppercase"
                  >
                    {component.name}
                  </h2>
                  <div className="mt-2">
                    <HealthChip state={component.state} />
                  </div>
                  <p className="mt-2 text-xs text-muted-foreground">{component.detail}</p>
                </section>
              ))}
            </div>

            <p className="tabular text-xs text-muted-foreground">
              Checked {formatDateTime(data.checkedAt)}
            </p>

            {/*
              What this screen used to show here was fabricated: consumer lag per
              group and a dead-letter depth that no process had measured. Phase 7
              brings the metric set, the dashboards and the runbooks together, and
              a number is worth showing when there is something to do about it.
            */}
            <Panel
              title="Pipeline depth and consumer lag"
              description="Not measured yet, so not shown."
              bodyClassName="p-4"
            >
              <p className="text-sm">
                Consumer-group lag and dead-letter depth belong to Kafka rather than to this API,
                and nothing here measures them today.
              </p>
              <p className="mt-2 text-sm text-muted-foreground">
                They arrive in Phase 7 with the metric set and the runbooks that say what to do
                about a number that is climbing. Until then this panel is empty rather than
                decorative — a figure nobody measured is worse than no figure, because somebody
                quotes it.
              </p>
            </Panel>
          </div>
        )}
      </QueryState>
    </AppShell>
  );
}
