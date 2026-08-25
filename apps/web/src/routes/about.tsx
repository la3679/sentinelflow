import { createFileRoute } from "@tanstack/react-router";

import { AppShell } from "@/components/app-shell";
import { PageHeader, Panel } from "@/components/panel";

export const Route = createFileRoute("/about")({
  head: () => ({
    meta: [
      { title: "About & disclaimer — SentinelFlow" },
      {
        name: "description",
        content:
          "SentinelFlow is an independent educational portfolio project using only fictional synthetic transaction data.",
      },
      { property: "og:title", content: "About & disclaimer — SentinelFlow" },
      {
        property: "og:description",
        content: "Independent educational project built on fictional synthetic transaction data.",
      },
    ],
  }),
  component: AboutPage,
});

function AboutPage() {
  return (
    <AppShell>
      <PageHeader
        title="About SentinelFlow"
        description="Scope, disclaimers and the boundary between this console and the systems it represents."
      />
      <div className="grid gap-4 lg:grid-cols-2">
        <Panel title="Independent project disclaimer" bodyClassName="space-y-3 p-4 text-sm">
          <p>
            SentinelFlow is an independent, educational, open-source portfolio project. It is not
            affiliated with, endorsed by, or derived from any bank, payment network, financial
            institution, regulator or employer.
          </p>
          <p>
            The console executes no real transactions, moves no money, and makes no real financial,
            credit or compliance decisions about any person or organisation.
          </p>
          <p>
            Nothing in this interface constitutes financial, legal or compliance advice, and no
            claim is made about its suitability for production fraud operations.
          </p>
        </Panel>

        <Panel title="Synthetic data statement" bodyClassName="space-y-3 p-4 text-sm">
          <p>
            Every transaction, account, merchant, device, alert, score and analyst identifier shown
            in this console is fictional and generated from a fixed seed for demonstration purposes.
          </p>
          <p>
            Identifiers deliberately use non-realistic formats such as{" "}
            <span className="tabular">ACC-000123</span>, <span className="tabular">MER-0042</span>{" "}
            and <span className="tabular">TXN-000517</span>. There are no real names, addresses,
            card numbers or other personal identifiers anywhere in the dataset.
          </p>
          <p>
            Monetary amounts are carried as decimal strings with an explicit currency code and are
            never processed with floating-point arithmetic.
          </p>
        </Panel>

        <Panel title="What this console is" bodyClassName="space-y-3 p-4 text-sm">
          <p>
            A review surface for analysts: triaging risk-scored transactions, working an alert
            queue, recording investigation decisions, and observing pipeline health.
          </p>
          <p>
            Risk scores are an ordering signal for human review. They are not a determination that
            any activity is fraudulent.
          </p>
        </Panel>

        <Panel title="What lives outside this repository" bodyClassName="space-y-3 p-4 text-sm">
          <p>
            Authentication, authorization, risk rules, machine-learning scoring, streaming and
            persistence are implemented in a separate backend monorepo built with Spring Boot,
            Kafka, PostgreSQL and Python/FastAPI.
          </p>
          <p>
            This frontend currently reads from a temporary in-memory mock fixture layer so it runs
            with no backend. That layer will be replaced by the typed{" "}
            <span className="tabular">/api/v1</span> client without changing the screens.
          </p>
        </Panel>

        <Panel title="Accessibility" bodyClassName="space-y-3 p-4 text-sm lg:col-span-2">
          <p>
            The console targets WCAG 2.2 AA: semantic landmarks and heading order, full keyboard
            operation with visible focus, status communicated by icon and text rather than colour
            alone, table semantics for data views, managed dialog focus, and accessible validation
            messages. Motion is suppressed when the operating system requests reduced motion.
          </p>
          <p>
            If you find an accessibility defect, treat it as a bug in this project rather than an
            intended limitation.
          </p>
        </Panel>
      </div>
    </AppShell>
  );
}
