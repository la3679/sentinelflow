import { createFileRoute, Link } from "@tanstack/react-router";
import { ShieldAlert } from "lucide-react";

import { AppShell } from "@/components/app-shell";
import { PageHeader, Panel } from "@/components/panel";

export const Route = createFileRoute("/forbidden")({
  head: () => ({
    meta: [
      { title: "Access not permitted — SentinelFlow" },
      {
        name: "description",
        content:
          "The signed-in demonstration role is not permitted to view this SentinelFlow area.",
      },
      { property: "og:title", content: "Access not permitted — SentinelFlow" },
      {
        property: "og:description",
        content: "The current demonstration role cannot view this area of the console.",
      },
    ],
  }),
  component: ForbiddenPage,
});

function ForbiddenPage() {
  return (
    <AppShell>
      <PageHeader title="Access not permitted" description="HTTP 403 — Forbidden" />
      <Panel title="Why you are seeing this" bodyClassName="space-y-3 p-4 text-sm">
        <p className="flex items-start gap-2">
          <ShieldAlert aria-hidden="true" className="mt-0.5 size-4 text-risk-high-foreground" />
          <span>
            The role selected for this demonstration session does not include access to the
            requested area. Switch the simulated role in the header, or return to a permitted
            screen.
          </span>
        </p>
        <p className="text-muted-foreground">
          Role handling in this console is a user-experience affordance only. Authorization
          decisions are made by the backend services, never in the browser.
        </p>
        <p>
          <Link to="/" className="underline underline-offset-4">
            Back to operations overview
          </Link>
        </p>
      </Panel>
    </AppShell>
  );
}
