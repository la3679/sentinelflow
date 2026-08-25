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

## Phase 1 — Monorepo and developer foundation · M0

**Deliverables**

- Monorepo layout (`apps/`, `contracts/`, `docs/`, `infra/`, `scripts/`)
- `.editorconfig`, `.gitignore`, `.gitattributes`, `.dockerignore`, `.env.example`
- Maven Wrapper for `apps/api`; `uv` project for `apps/scoring`; normalized `apps/web`
- `compose.yaml` baseline: PostgreSQL 18.6, Kafka 4.2.1 (KRaft), Prometheus, Grafana, OTel Collector
- `Makefile` developer command surface, with PowerShell-compatible equivalents documented
- `.claude/rules/` and hooks, verified against the installed Claude Code schema
- Per-component CI workflows
- ADR-0002 monorepo and service boundaries

**Gate** — clean-clone bootstrap works · each app builds and has a health or smoke test · CI green.

---

## Phase 2 — Contracts, domain, and database · M1

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
- Reproducible model training: logistic-regression baseline, tree-based model if it materially
  helps, Isolation Forest as an unsupervised comparison
- Time-aware / group-aware splitting; precision, recall, F1, PR-AUC, false-positive rate,
  confusion matrix, alert volume, and inference latency reported — **never accuracy as the
  headline**
- Model registry entry, artifact checksum, model card, evaluation and limitations docs
- FastAPI scoring service (`/health/live`, `/health/ready`, `/v1/model`, `/v1/score`)
- Spring scoring client with timeout, bounded retry, and circuit breaker
- Persisted risk assessments with full model and policy metadata
- ADR-0008 scoring-service boundary · ADR-0010 model and evaluation choice

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
- ADR-0011 SSE versus WebSockets

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
- ADR-0013 observability approach

**Gate** — one transaction trace can be followed end to end · dashboards load with data ·
redaction tests pass · a documented failure drill succeeds.

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
- ADR-0014 deployment and local-first strategy

**Gate** — every README command works · diagrams match the code · no placeholders or broken links ·
actual test and performance evidence committed.

---

## Phase 10 — Release · M5

**Deliverables**

- All pull requests merged safely; `main` CI green
- `main` ruleset active
- `v1.0.0` if every v1 criterion is genuinely met, otherwise an honest prerelease
- Changelog and release notes with features, setup, migrations, security notes, known limitations,
  and evidence links
- Repository metadata and topics
- Optional free Lovable preview, clearly labelled as UI-only if it is
- Final handoff report

**Gate** — clean `main` · remote SHA verified · all required CI green · the tag points at verified
code · no false claims anywhere.

---

## Post-v1 roadmap (approval required)

Tracked as separate roadmap issues, never allowed to delay or destabilise v1:

1. Synthetic customer-onboarding demonstration.
2. MySQL-to-PostgreSQL migration lab.
