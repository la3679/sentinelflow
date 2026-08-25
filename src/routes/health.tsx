import { createFileRoute } from "@tanstack/react-router";
import { RotateCcw } from "lucide-react";

import { AppShell } from "@/components/app-shell";
import { HealthChip } from "@/components/chips";
import { QueryState } from "@/components/data-state";
import { PageHeader, Panel, StatTile } from "@/components/panel";
import { Button } from "@/components/ui/button";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { formatDateTime } from "@/domain/labels";
import { useGetSystemHealthQuery } from "@/api/sentinelApi";

export const Route = createFileRoute("/health")({
  head: () => ({
    meta: [
      { title: "System health — SentinelFlow" },
      {
        name: "description",
        content:
          "Status of the API, scoring service, Kafka cluster and database, plus consumer lag and dead-letter queue depth.",
      },
      { property: "og:title", content: "System health — SentinelFlow" },
      {
        property: "og:description",
        content: "Component status, consumer lag and dead-letter queue depth for the pipeline.",
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
        description="Reported state of the services behind the console. Values are simulated in this build."
        actions={
          <Button
            type="button"
            variant="outline"
            onClick={() => void query.refetch()}
            disabled={query.isFetching}
          >
            <RotateCcw aria-hidden="true" className="size-4" />
            Refresh
          </Button>
        }
      />
      <QueryState
        isLoading={query.isLoading}
        isError={query.isError}
        error={query.error}
        data={query.data}
        onRetry={() => void query.refetch()}
        loadingLabel="Loading system health"
        loadingRows={6}
      >
        {(data) => (
          <div className="space-y-6">
            <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
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
                  <p className="tabular mt-2 text-xs text-muted-foreground">
                    Checked {formatDateTime(component.lastCheckedAt)}
                  </p>
                </section>
              ))}
            </div>

            <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
              <StatTile
                label="Dead-letter depth"
                value={String(data.pipeline.dlqDepth)}
                hint={data.pipeline.dlqTopic}
                emphasis={data.pipeline.dlqDepth > 0}
              />
              <StatTile
                label="Total consumer lag"
                value={String(
                  data.pipeline.consumerGroups.reduce((total, g) => total + g.lagMessages, 0),
                )}
                hint="Messages behind across all groups"
              />
              <StatTile
                label="Consumer groups"
                value={String(data.pipeline.consumerGroups.length)}
                hint="Reporting to the console"
              />
              <StatTile
                label="Snapshot time"
                value={formatDateTime(data.checkedAt).slice(11)}
                hint={formatDateTime(data.checkedAt).slice(0, 10)}
              />
            </div>

            <Panel
              title="Consumer lag by group"
              description="Lag is the number of messages a consumer group is behind on its topic."
              bodyClassName="overflow-x-auto"
            >
              <Table>
                <caption className="sr-only">Consumer lag per group and topic</caption>
                <TableHeader>
                  <TableRow>
                    <TableHead scope="col">Consumer group</TableHead>
                    <TableHead scope="col">Topic</TableHead>
                    <TableHead scope="col" className="text-right">
                      Lag (messages)
                    </TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {data.pipeline.consumerGroups.map((group) => (
                    <TableRow key={group.groupId}>
                      <TableCell className="tabular">{group.groupId}</TableCell>
                      <TableCell className="tabular">{group.topic}</TableCell>
                      <TableCell className="tabular text-right">{group.lagMessages}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </Panel>
          </div>
        )}
      </QueryState>
    </AppShell>
  );
}
