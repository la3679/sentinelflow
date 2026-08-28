# ADR-0014 — Where the console's four remaining screens get their data

- **Status:** Accepted
- **Date:** 2026-08-28
- **Related:** [ADR-0002](0002-monorepo-and-service-boundaries.md),
  [ADR-0008](0008-scoring-service-boundary.md),
  [ADR-0011](0011-risk-banding-and-the-final-score.md),
  [ADR-0012](0012-operator-authentication.md),
  [`API_MIGRATION_AUDIT.md`](../frontend/API_MIGRATION_AUDIT.md)

## Context

The console invents four endpoints that no service has ever served: `GET /overview`,
`GET /reports`, `GET /model-policy` and `GET /health`. They are the last of the four pieces the
migration audit identified, and unlike the first three they cannot be settled by mapping fields —
each one needs a decision about **where the data comes from**, and two of them touch a service
boundary.

Everything else in the console now reads the API. These four are what is left, and Phase 6's
deliverables name all four screens, so "delete the screen" is not available for any of them.

Three facts established while building the rest of the migration, none of which the audit had:

1. **`GET /models/active` is in the contract at Phase 4 and has no handler.** Nor is one enough on
   its own: `model_registry` is a real table with constraint tests and **no code has ever written a
   row to it**. The registry of record is the one `apps/scoring` loads from disk and publishes at
   `GET /v1/model`.
2. **The overview wants three systems' worth of data.** Alert counts are the API's and already
   exist. A throughput series has no source anywhere. Latency percentiles, consumer-group lag and
   dead-letter depth are Prometheus and Kafka — which is Phase 7's subject, arriving with the
   metric set, the dashboards and the runbooks that make them mean something.
3. **The system-health screen has the same problem in a smaller shape.** What it can honestly show
   is whether each part of the stack is answering. What it currently shows is fabricated consumer
   lag.

The rule that decides most of this is already written down and is not negotiable:
[`CLAUDE.md`](../../CLAUDE.md) forbids invented numbers, and `.claude/rules/frontend.md` forbids
dead controls and requires every screen to make it discoverable that the data is synthetic. A panel
of made-up latency percentiles fails both.

## Decision

### 1. `GET /models/active` is composed by the API from the scoring service and its own policy

The API implements the endpoint the contract already describes. It gets the model half by asking
the scoring service — through `ScoringClient`, which already has the timeout, the bounded retry and
the circuit breaker ADR-0008 requires — and the policy half from `RiskPolicyProperties`, which it
owns.

**The console's `thresholds` come from that policy object**, not from a new source:
`bandLowerBounds`, `alertFromBand` and `priorityByBand` are exactly the band boundaries and actions
the screen wants, and they are already validated at startup.

**Rejected: read `model_registry`.** The table has never been written to, so the endpoint would
answer a permanent `404` that looks like an outage. Populating it would mean deciding that the API
is the registry of record, which contradicts ADR-0008's placement of the model with the service
that serves it, and would need a promotion path, an audit trail and a rollback story — none of
which exists, and all of which is more than a read screen should drag in.

**Rejected: let the browser call the scoring service.** ADR-0002 §3 is explicit that the API is the
only backend the console talks to: one authorization boundary, one audit trail, one place to
rate-limit.

**The empty table is recorded as debt rather than quietly left.** `model_registry` and its
constraints stay, because the decision they anticipate — a registry with promotion and rollback —
is a real one for a later phase. Until then nothing reads it, and
[`PROJECT_STATE.md`](../../PROJECT_STATE.md) says so.

**A degraded answer is a real answer.** If the scoring service cannot be reached, the endpoint
returns the policy half with the model half absent and says which, rather than failing. The console
already renders a degraded assessment; a model screen that goes blank because scoring is restarting
would be less useful than one that says so.

### 2. `GET /system/health` is composed by the API, and covers only what it can observe today

A small endpoint under `/api/v1` reporting whether the API's own dependencies answer: PostgreSQL,
and the scoring service through the client that already talks to it.

**Kafka consumer lag and dead-letter depth are not in it.** They are Phase 7's, and they are the
figures the screen currently fabricates. The screen states that they arrive with the observability
work rather than showing a number nobody measured.

**Rejected: point the console at `/actuator/health`.** It is on a different base path from the
contract, its shape is Spring Boot's rather than this API's, `show-details` is
`when-authorized`, and a smoke test already asserts that the closed management endpoints answer
`401`. Coupling a screen to the actuator's serialisation would put a Spring Boot version bump on
the console's critical path.

### 3. The overview is composed in the client, from endpoints that exist

The landing page reads `GET /reports/alert-summary` for counts by status, priority and band, and
`GET /alerts` for the most recent work. It fires two requests rather than one, and that is the cost.

**Rejected: an aggregate `GET /overview`.** It would put a second implementation of risk-band
counting beside the report that already does it, and the two would disagree the first time one
changed. The audit named this trade and it is the one that decides it: a screen that can be
half-loaded is recoverable, and two counts of the same thing that disagree are not.

**The throughput series, the latency summary and the pipeline panels are removed until Phase 7.**
Every number in them today is invented. The screen says what is missing and when it arrives, which
is a truthful empty state rather than a decorative full one.

### 4. The reports screen shows the window the API can count, and nothing it cannot

It reads `GET /reports/alert-summary` over an operator-chosen window and renders the three
distributions it returns, every key present including the zeroes.

**The daily trend and the feedback-outcome aggregates are removed.** Neither has an endpoint. The
export the screen should offer instead already exists and has never been called by anything:
`GET /reports/alerts.csv`, which is a real download of a real window.

## Consequences

- Two API endpoints to build: `GET /models/active` and `GET /system/health`, both compositions of
  things that already exist, both with contract entries and integration tests.
- Four console screens stop reading fixtures, and `src/mocks/` is deleted with the last of them.
  `USING_MOCK_DATA` and the banner it drives go at the same time.
- **Three panels of invented numbers disappear from the console**, and what replaces them is a
  sentence about Phase 7 rather than a chart. The screens get smaller, and honest.
- The overview fires two requests and can be half-loaded. Both are handled by the loading and error
  states every data view already has.
- `model_registry` stays empty and unread, recorded as debt. The day a promotion path is wanted,
  this decision is the one to reopen.
- The reports screen gains the CSV export, which is the first client for an endpoint that has been
  tested and unused since Phase 5.

## Revisit if

- Phase 7 lands the metric set and the dashboards. The throughput, latency and lag panels then have
  real sources, and the overview and health screens should grow them — through the API, and
  measured rather than composed from a scrape the console reads itself.
- The overview's two requests become five. At that point the argument against an aggregate endpoint
  weakens, and this decision should be reopened rather than worked around one request at a time.
- Anything needs to write to `model_registry`. That is a decision about where the registry of
  record lives, not an implementation detail, and it supersedes §1 rather than extending it.
