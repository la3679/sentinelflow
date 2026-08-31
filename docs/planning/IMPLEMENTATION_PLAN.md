# SentinelFlow — Implementation Plan

Complete phase and backlog plan. Each phase ends with tests, documentation, a checkpoint, a push,
and generally a pull request. Work proceeds in **vertical, demonstrable increments** — never a
single large code dump.

Milestones: `M0 Foundation` · `M1 Event Pipeline` · `M2 Risk and Alerts` ·
`M3 Analyst Experience` · `M4 Reliability and Security` · `M5 v1 Release`

---

## Phase 0 — Research, product baseline, Lovable, and repository · M0

**Deliverables**

- [x] Research log with primary-source version and licence decisions
- [x] Product brief (`PRODUCT_REQUIREMENTS.md`)
- [x] ADRs 0001, 0003, 0004, 0009
- [x] `CLAUDE.md` and `PROJECT_STATE.md`
- [ ] Lovable design system and representative screens
- [ ] Lovable-created GitHub repository `la3679/sentinelflow`
- [ ] Secret scan, then public visibility
- [ ] Repository description and topics
- [ ] Verified local clone; all later commands run from its root
- [ ] Milestones, labels, and the initial issue set

**Gate** — repository and Lovable sync verified · clone root, `origin`, branch, and Lovable HEAD
verified · no secrets · initial preview builds · state file holds the exact repo, branch, and SHA.

---

## Phase 1 — Monorepo and developer foundation · M0 — **COMPLETE** 2026-08-25

**Delivered**

- [x] Monorepo layout: `apps/{api,scoring,web}`, `infra/`, `scripts/`, `docs/`. The root is a Bun
      workspace — deviation recorded in ADR-0002. `contracts/` waits for Phase 2's first schema
      rather than being created empty.
- [x] `.editorconfig`, `.gitignore`, `.gitattributes`, `.dockerignore`, `.env.example`
- [x] Maven Wrapper 3.3.4 (script-only, SHA-256 verified against two sources) for `apps/api`;
      `uv` project with a committed `uv.lock` for `apps/scoring`; `apps/web` normalized
- [x] `compose.yaml`: PostgreSQL 18.6, Kafka 4.2.1 (KRaft), the three applications, Prometheus,
      Grafana — all seven with health checks and health-gated `depends_on`
- [x] Multi-stage container images for all three applications, non-root, health-checked, scanned
- [x] `Makefile` command surface plus a native PowerShell runner (`scripts/dev/sf.ps1`), because
      the reference Windows machine has no `make`
- [x] `.claude/` status line, four hooks, rules, and a checkpoint helper, verified against the
      current Claude Code schema (R-2026-08-25-14)
- [x] Per-component CI: `ci-repo`, `ci-api`, `ci-scoring`, `ci-web`, `ci-containers`
- [x] Community health files, Dependabot across five ecosystems, labels, milestones
- [x] `main` protected by a ruleset: pull requests, nine required checks, no bypass actors
- [x] ADR-0002, `LOVABLE_GITHUB_WORKFLOW.md`, `BRANCH_PROTECTION.md`, a verified README

**Deliberately deferred, with the phase that delivers it**

- ~~The **OpenTelemetry Collector** is not in `compose.yaml`.~~ **Delivered in Phase 7**, alongside
  Tempo, once something exported to it. The reason it waited stands: a collector that receives
  nothing is decoration rather than infrastructure.
- `make seed`, `make replay` and `make test-integration` exist, are listed by `make help`, and
  **fail with the phase that delivers them** rather than silently succeeding.

**Gate — met, with evidence**

| Criterion                            | Evidence                                                                             |
| ------------------------------------ | ------------------------------------------------------------------------------------ |
| Clean-clone bootstrap works          | Fresh clone at `55efe6a`: bootstrap, frozen install, all three apps built and tested |
| Each app builds and has a smoke test | api 5/5 · scoring 6/6 · web 24/24 + 58/58 browser · stack smoke 23/23                |
| CI green                             | All six workflows passing                                                            |

---

## Phase 2 — Contracts, domain, and database · M1 — **NEXT**

**Deliverables**

- OpenAPI baseline in `contracts/openapi/`
- AsyncAPI document and JSON Schemas in `contracts/asyncapi/`, `contracts/schemas/`
- Domain model: customers, accounts, merchants, transactions, risk_assessments, alerts,
  alert_actions, analyst_feedback, model_registry, outbox_events, processed_events, audit_log,
  users, roles
- Initial Flyway migrations with referential integrity, check constraints, and uniqueness
- Testcontainers PostgreSQL migration tests
- Repeatable seed framework, separate from schema migrations
- ER and data-flow diagrams
- ADR-0006 event schema/version strategy · ADR-0007 Flyway and money representation

**Gate** — migrations run from an empty database · constraints tested · contracts validate in CI ·
docs match the schema.

---

## Phase 3 — Transaction ingestion, outbox, and Kafka · M1

**Deliverables**

- Single and bounded batch ingestion with full validation
- Idempotency-key enforcement
- RFC 9457 problem-details error bodies and correlation IDs
- Transactional outbox — transaction and outbox row in one commit
- Outbox publisher and Kafka production of `transaction.created.v1`
- Idempotent consumer with `processed_events`, bounded retry with jitter, and DLQ routing
- Authorized, audited DLQ reprocessing
- Testcontainers integration tests over real PostgreSQL and Kafka
- Metrics and tracing baseline
- ADR-0005 outbox and delivery semantics

**Gate** — duplicate submission cannot duplicate business data · an event survives temporary Kafka
unavailability through the outbox · retry and DLQ tests pass · trace and correlation evidence
exists.

---

## Phase 4 — Synthetic data and scoring · M2

**Deliverables**

- Deterministic seed-based scenario generator with a manifest (seed, version, counts, label
  distribution, checksum)
- Versioned, tested feature pipeline with leakage prevention
- Transparent rules-only baseline
- Account-context assembler in `apps/api`, shared by the runtime scoring call and the labelled
  training export — one implementation, so train/serve skew is impossible rather than watched for
  (ADR-0010 §1)
- Labelled dataset export: the exact `ScoreRequest` body plus the planted `ScenarioType`, written
  offline and never persisted to the schema
- Reproducible model training: logistic-regression baseline, tree-based model if it materially
  helps, Isolation Forest as an unsupervised comparison; the contract's 0–100 `modelScore` is
  calibrated underneath and its calibration measured, not assumed (ADR-0010 §4)
- Time-aware / group-aware splitting; precision, recall, F1, PR-AUC, false-positive rate,
  confusion matrix, alert volume, and inference latency reported — **never accuracy as the
  headline**
- Model registry entry, artifact checksum, model card, evaluation and limitations docs
- FastAPI scoring service (`/health/live`, `/health/ready`, `/v1/model`, `/v1/score`)
- Spring scoring client with timeout, bounded retry, and circuit breaker
- Persisted risk assessments with full model and policy metadata
- `make replay`: the operational scenarios from §8.3 that nothing else produces — a temporary
  scoring-service outage and a malformed event reaching the dead-letter path. The transaction
  shapes are `make seed`'s; the HTTP replay endpoint is API surface and waits for the
  authorization and rate limiting of later phases
- ADR-0008 scoring-service boundary · ADR-0010 model and evaluation choice · ADR-0011 risk banding and the final score

**Gate** — training reproducible from a documented command · evaluation report generated · model
checksum and version stored · service contracts and failure behaviour tested.

---

## Phase 5 — Alerts, investigations, and audit · M2

**Deliverables**

- Versioned risk-band policy and alert creation
- Server-enforced alert state machine with conflict responses
- Assignment, notes, analyst feedback, and audit history
- Role authorization for analyst, administrator, and auditor
- Optimistic concurrency control
- Paginated reporting endpoints and a formula-injection-safe CSV export
- ADR-0012 authentication approach

**Gate** — every state change audited · invalid and concurrent changes handled · auditor mutation
attempts fail as expected · API documented.

---

## Phase 6 — Operations frontend · M3

**Deliverables**

- Typed RTK Query API layer replacing the Lovable mock fixtures
- Authentication flow against the real API
- Overview, live transactions, alert queue, alert detail, transaction detail, reports,
  model/policy, and system-health screens
- Loading, empty, error, and permission-denied states everywhere
- Accessibility tests (axe) and Playwright end-to-end journeys
- Current screenshots with synthetic data
- [ADR-0015](../adr/0015-live-updates-polling-and-server-sent-events.md) live updates: bounded
  polling now, SSE when there is a stream to carry

**Gate** — no dead controls · keyboard and accessibility checks pass · the core end-to-end journey
passes · Lovable diffs reviewed and merged safely.

---

## Phase 7 — Observability and resilience · M4

**Deliverables**

- Complete low-cardinality metric set across API, outbox, Kafka, scoring, and alerts
- Structured JSON logging with redaction tests
- W3C trace propagation across HTTP and Kafka
- Version-controlled Grafana dashboards: platform, API/database, Kafka/outbox, scoring, alerts
- Resilience drills for scoring-service and Kafka failure
- Runbooks for API down, scoring down, consumer lag, outbox backlog, DLQ growth, connection
  saturation, high error rate, slow report query, model artifact load failure
- [ADR-0016](../adr/0016-observability-signals-and-their-boundaries.md) observability: what each
  signal answers, and what none of them may carry

**Gate** — one transaction trace can be followed end to end · dashboards load with data ·
redaction tests pass · a documented failure drill succeeds.

**Status: four of six deliverables merged** as PRs #70 to #73. The metric set, structured logging
with redaction, trace propagation and the dashboards are done; the resilience drills and the runbooks
are not. Two gate criteria are met with evidence, one is partly met and one has not been attempted —
`PROJECT_STATE.md` §"Acceptance criteria status — Phase 7 gate" holds the row-by-row position.

---

## Phase 8 — Security and quality hardening · M4

**Deliverables**

- STRIDE-style threat model
- Authentication hardening, rate limits, payload and pagination bounds
- CSV formula-injection defence, log sanitization, safe error responses
- CodeQL, dependency review, container scan, secret scan in CI
- SBOM and release checksums
- Dependency policy and Dependabot configuration
- Least-privilege GitHub Actions permissions with pinned third-party actions

**Gate** — no unresolved critical or high findings without documented false-positive evidence ·
secret scan clean · workflows reviewed · threat controls traceable to tests and docs.

---

## Phase 9 — Performance, documentation, and clean-clone validation · M5

**Deliverables**

- Load and query benchmarks with a documented reference environment
- Measured optimizations with before/after query plans
- Full README with the five required Mermaid diagrams
- Complete documentation index
- Clean-clone verification of every README command
- Demo walkthrough and screenshots
- Link, badge, and screenshot audit
- An ADR for the deployment and local-first strategy — number allocated when it is written, for the
  same reason

**Gate** — every README command works · diagrams match the code · no placeholders or broken links ·
actual test and performance evidence committed.

---

## Phase 10 — Release · M5

**Deliverables**

- All pull requests merged safely; `main` CI green
- `main` ruleset active
- **Real operator identity, closing the assignment hole Phase 6 deferred** — the smallest
  architecturally correct lookup that lets an alert be given to a named analyst, with no hardcoded
  identifiers and no invented users, server-side authorization still authoritative, contracts and
  console updated together, optimistic concurrency handled, tests and an end-to-end journey, and
  verification against the real stack rather than only Testcontainers. `PROJECT_STATE.md`,
  "Required before v1 — carried forward" §1 holds the binding definition of done. An ADR records the
  decision; its number is allocated when it is written.
- `v1.0.0` if every v1 criterion is genuinely met, otherwise an honest prerelease
- Changelog and release notes with features, setup, migrations, security notes, known limitations,
  and evidence links
- Repository metadata and topics
- Optional free Lovable preview, clearly labelled as UI-only if it is
- Final handoff report

**Gate** — clean `main` · remote SHA verified · all required CI green · an alert can be assigned to a
named operator · the tag points at verified code · no false claims anywhere.

**Two items this gate does not cover, and no phase may claim.** A screen-reader pass and a manual
authenticated walkthrough of the console both need a person. They stay listed as outstanding human
verification in `PROJECT_STATE.md` §"Required before v1" until somebody does them; an automated run
is never evidence that either happened.

---

## Post-v1 roadmap (approval required)

Tracked as separate roadmap issues, never allowed to delay or destabilise v1:

1. Synthetic customer-onboarding demonstration.
2. MySQL-to-PostgreSQL migration lab.
