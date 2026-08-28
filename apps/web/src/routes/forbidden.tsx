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
            The roles on your account do not include access to the requested area. Roles come from
            the account you signed in with — they are not something this console lets you choose —
            so the way to a different answer is a different account.
          </span>
        </p>
        <p className="text-muted-foreground">
          Role handling in this console is a user-experience affordance only. The refusal you are
          reading came from the API, which makes every authorization decision from the token rather
          than from anything the browser was told.
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
