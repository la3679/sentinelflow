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
              group and a dead-letter depth that no process had measured. Both are
              measured now, and they are still not here - they are read from the
              broker with an admin client and exported to Prometheus, and a number
              worth acting on belongs beside the runbook that answers it rather
              than on a page with no way to act.
            */}
            <Panel
              title="Pipeline depth and consumer lag"
              description="Measured, and deliberately not shown here."
              bodyClassName="p-4"
            >
              <p className="text-sm">
                Consumer-group lag and dead-letter depth belong to Kafka rather than to this API.
                Both are measured and exported to Prometheus, with Grafana dashboards and alerting
                rules over them.
              </p>
              <p className="mt-2 text-sm text-muted-foreground">
                They stay there rather than being copied onto this screen, because each one is
                answered by a runbook and a number with nothing to do about it is decoration. This
                page answers a different question — whether the components this console depends on
                are responding right now.
              </p>
            </Panel>
          </div>
        )}
      </QueryState>
    </AppShell>
  );
}
