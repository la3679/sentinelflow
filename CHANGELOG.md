# Changelog

Notable changes to SentinelFlow, in [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) order,
versioned with [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Every figure here came from a run that happened, on the date recorded beside it.
[`docs/testing/TEST_RESULTS.md`](docs/testing/TEST_RESULTS.md) holds the commands and the evidence.

## Unreleased

### Fixed

- **Release artefacts are attached by the release workflow again, and the step is re-runnable.**
  `sbom.yml`'s attach job runs only on a release, so its first execution was the v1.0.0 release and
  it failed there: it downloads the SBOM bundle and never checks out the source, so `gh release
upload` had no repository to infer. A release event also runs its workflows from the tag's own
  commit, which means a broken attach step cannot be repaired for a release that already exists by
  fixing `main` and republishing. `workflow_dispatch` now takes an optional `release_tag`, so the
  step can be re-run against an existing release. v1.0.0's five artefacts were attached that way and
  their checksums verified.

  Neither change touches the released software. `v1.0.0` continues to point at `147a6c9`.

## [1.0.0] — 2026-09-01

The first release. Everything below is new, so this entry describes the system rather than a diff
against something earlier.

SentinelFlow is an independent, educational demonstration of an event-driven transaction-risk and
fraud-operations platform. It runs entirely on **synthetic data** and is not a bank system, a
compliance product, or a production fraud-decision engine.

### Added

**Ingestion.** `POST /api/v1/transactions` behind an API key compared in constant time, idempotent
per account, size-capped and rate-limited. The transaction row and its outbox event commit in one
transaction, so an accepted transaction is an event that will be delivered.

**Event delivery.** A polling relay publishes each outbox row to Kafka keyed by account. The
consumer claims every event in `processed_events` before doing any work, so at-least-once delivery
becomes effectively-once processing. Five topics, created explicitly by a one-shot service rather
than by broker auto-creation. Undeliverable events are dead-lettered with their envelope, or — when
the envelope itself cannot be read — logged and counted rather than copied onto an operational
topic.

**Risk scoring.** Seven transparent in-process indicators combined with a calibrated scikit-learn
model reached over HTTP, under a versioned policy that bands the result. Every assessment carries
its model version, feature version and reason codes.

**Graceful degradation.** An unreachable scoring service degrades an assessment to the rules alone
rather than losing it, behind a timeout, a bounded retry and a circuit breaker. A degraded
assessment says it is degraded; nothing silently substitutes a number.

**Alert lifecycle.** A banded assessment opens an alert, its first history row and its
`alert.created` event in one transaction. An analyst then works a state machine, with every
transition checked against a concurrent change by version and answered with `409` when it is stale.

**Named assignment.** An alert can be given to a named analyst. `GET /api/v1/operators` lists who
may hold one, `Alert.assignee` resolves the identifier to a person, and the login response carries
the operator's own identifier so an analyst can take an alert themselves. Assigning an auditor, the
`system` principal, or an unknown identifier is refused by the server, whatever a client sends
([ADR-0019](docs/adr/0019-resolving-an-assignee-to-a-person.md)).

**Reporting.** `GET /reports/alert-summary` over a half-open window, with every key present
including the zeroes, and a CSV export capped at 10,000 rows.

**Operations console.** Overview, transaction feed, alert queue, investigation workspace, reports,
model and policy, and system health — every screen reading the real API, each with loading, empty,
error-with-retry and bounded states. WCAG 2.2 AA is the target; axe runs across every route at two
viewports in a real browser.

**Observability.** Prometheus metrics, five provisioned Grafana dashboards, thirteen alerting rules,
ten runbooks, and W3C trace context that survives the asynchronous hop through Kafka.

**Security.** Stateless operator authentication with roles enforced server-side, an ingestion
credential, per-caller rate limiting ahead of the security chain, RFC 9457 problem responses that
carry no stack trace or SQL, log redaction verified at `root=DEBUG`, and every published port bound
to `127.0.0.1`. The posture, including what is deliberately still open, is in
[`docs/security/THREAT_MODEL.md`](docs/security/THREAT_MODEL.md).

### Setup

`make bootstrap` verifies prerequisites and generates five random secrets into a git-ignored `.env`;
`make up` builds and starts the ten-container stack; `make seed` writes deterministic demo data.
Windows without `make` uses `.\scripts\dev\sf.ps1 <target>`, which mirrors every target.

Nothing is committed that a deployment would have to rotate: Compose refuses to start when a secret
is missing rather than falling back to something guessable. Full quick start in the
[README](README.md#quick-start), variable by variable in [`.env.example`](.env.example).

### Migrations

Twelve Flyway migrations, `V1` through `V12`, applied automatically at API startup with
`ddl-auto: validate`. A migration is immutable once merged; a mistake is fixed by a new one.
`V1` inserts the `system` principal as reference data and the API refuses to start without it, so
`users` must never be truncated — [`docs/operations/TROUBLESHOOTING.md`](docs/operations/TROUBLESHOOTING.md)
has the safe way back to a clean database.

There is no upgrade path to write, because there is no earlier release to upgrade from.

### Security notes

- The local stack is **not a deployment target**. It binds to the local machine, holds only
  synthetic data, and has never been hardened for exposure
  ([ADR-0018](docs/adr/0018-deployment-and-the-local-first-strategy.md)).
- Ingestion carries **one shared key**, so it authenticates a caller without identifying which one
  (threat model T-09).
- `/actuator/prometheus` is **unauthenticated**, because a scrape cannot hold a token that expires
  every thirty minutes. Aggregate series with bounded labels — no identifier, amount or payload
  (T-04).
- **A token cannot be revoked** before it expires; thirty minutes is the whole of how long a
  withdrawn role keeps working. Accepted deliberately (T-08).
- The **rate limiter is per API instance**, and a restart forgets who was being limited.
- CodeQL reports **0 open alerts** on `refs/heads/main`. Ten results are dismissed with their
  arguments recorded — one disabled-CSRF finding on a stateless API with no cookie to forge, and
  nine framework-mandated unused parameters.

### Known limitations

- **No screen-reader pass has been done.** axe finds roughly a third of real accessibility issues,
  and every accessibility check here is one of the cheaper two-thirds. This needs a person and has
  not happened.
- **No manual authenticated walkthrough of the console has been done** by a person. Every screen's
  endpoints have been called with a real token, and an automated browser suite drives the console
  against the real stack, but neither is somebody using it.
- **Operators are seeded, not managed.** Nothing invites, disables, or changes the role of one.
- **Google Fonts is loaded from a remote host**, so a stack that describes itself as local-first has
  one runtime dependency on a third party.
- **Three operator actions have no endpoint** — reprocessing a dead-lettered event, reviving a
  `FAILED` outbox row, and rescoring a degraded assessment. Each has a manual procedure in
  [`docs/operations/RUNBOOKS.md`](docs/operations/RUNBOOKS.md) and no phase ever owned the endpoint.
- **`audit_log` exists and nothing writes it.** Alert history is carried by `alert_actions`, which
  records the actor, role and moment of every mutation, so nothing is missing — but the table is
  unused rather than coincidentally empty.
- **Nothing navigates from a transaction to its alert**; the route that exists is alert to
  transaction.
- **Nothing asserts that the Compose stack's Kafka topics match the AsyncAPI contract.** The names
  are written in two places, and a drift between them is caught by the stack failing to publish
  rather than by a test that names it.
- **Performance is measured on one laptop**, on the dataset named in the report. Sustained
  throughput, cold starts and the console are not measured at all.

The full list, including entries too small for release notes, is under "Known issues and technical
debt" in [`PROJECT_STATE.md`](PROJECT_STATE.md).

### Evidence

Measured on `27cf15c`, 2026-09-01, with the whole stack running:

| What                       | Result                                                         |
| -------------------------- | -------------------------------------------------------------- |
| API suites                 | 259 unit + 337 integration passed, LINE 0.9017 / BRANCH 0.8030 |
| Scoring suite              | 187 passed, 96.97% coverage                                    |
| Console unit               | 41 passed                                                      |
| Console browser and axe    | 92 passed across eight routes at two viewports                 |
| Console against real stack | 5 passed, nothing stubbed                                      |
| Smoke against the stack    | 23 passed, 0 failed                                            |
| Contracts, docs, format    | all pass — 305 documentation links, 0 broken                   |
| CI on `main`               | eight workflows, all green                                     |
| Read latency (2026-08-31)  | `GET /alerts` p50 22 ms, p95 37 ms, p99 110 ms                 |

Commands and full detail: [`docs/testing/TEST_RESULTS.md`](docs/testing/TEST_RESULTS.md) and
[`docs/performance/BENCHMARK.md`](docs/performance/BENCHMARK.md).

[1.0.0]: https://github.com/la3679/sentinelflow/releases/tag/v1.0.0
