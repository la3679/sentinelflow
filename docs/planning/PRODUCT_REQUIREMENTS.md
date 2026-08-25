# SentinelFlow — Product Requirements

**Status:** baseline for v1 · **Date:** 2026-08-25

## 1. What this is

SentinelFlow is an **independent, educational, open-source portfolio project**. It demonstrates
how an event-driven financial-transaction system can ingest synthetic transactions, assess risk
with transparent rules plus a versioned machine-learning model, raise explainable alerts, support
analyst investigations, and expose operational health through a polished console and an
observability stack.

**It is not** an official product of any company, a real bank system, a regulatory-compliance
product, or a production fraud-decision engine. It uses **fictional entities and synthetic data
only**. It moves no money, freezes no accounts, and makes no adverse decision about any real
person.

## 2. Core demonstration flow

```text
Synthetic transaction generator or REST client
  → Spring Boot transaction API
  → PostgreSQL transaction + transactional outbox (one atomic commit)
  → Kafka transaction.created.v1
  → Spring Boot risk orchestration consumer
  → Python FastAPI / scikit-learn scoring service
  → risk assessment + alert + audit history in PostgreSQL
  → Kafka risk/alert events + authenticated SSE live updates
  → React operations console
```

Prometheus, Grafana, structured logs, health checks, traces, CI, security scanning, and automated
tests surround the whole workflow.

## 3. Definition of product success

A new developer can clone the repository, follow the README, start the system, seed deterministic
data, replay transactions, watch alerts arrive, review an alert, inspect metrics, run the tests,
and understand the architecture — **without private knowledge and without manually editing the
database**.

## 4. Personas and permissions

| Capability                                  | Analyst | Administrator |     Auditor     |
| ------------------------------------------- | :-----: | :-----------: | :-------------: |
| View transactions, alerts, reports          |   yes   |      yes      |       yes       |
| Filter and search                           |   yes   |      yes      |       yes       |
| Assign an alert to self                     |   yes   |      yes      |       no        |
| Add investigation notes                     |   yes   |      yes      |       no        |
| Transition alert state                      |   yes   |      yes      |       no        |
| Escalate an alert                           |   yes   |      yes      |       no        |
| View model / rule configuration metadata    |   no    |      yes      | yes (read-only) |
| View system health and operational metrics  | limited |      yes      | yes (read-only) |
| Change demo thresholds (validated settings) |   no    |      yes      |       no        |

Every one of these checks is **enforced on the server**. Hiding a control in the UI is never
treated as authorization.

## 5. Primary user journey

1. Sign in with a seeded demo identity.
2. Start or observe a deterministic transaction replay.
3. Ingestion returns `202 Accepted` with a correlation identifier.
4. The transaction is published asynchronously through the outbox.
5. Scoring produces a rule score, model score, final score, risk band, and reason codes.
6. High-risk transactions create alerts.
7. The dashboard receives a live update.
8. An analyst opens the alert, reviews the evidence, and records a disposition.
9. The complete audit trail is preserved.
10. Metrics and traces let an operator follow one transaction across every component.

## 6. Functional requirements

### 6.1 Authentication and authorization

Self-contained demo authentication in the Spring Boot API using Spring Security; adaptive password
hashing; short-lived JWTs signed with a key supplied through environment configuration and **never
committed**. No public self-registration in v1. Analyst, administrator, and auditor demo accounts
are seeded **only under an explicit demo profile**, with passwords generated during bootstrap
rather than committed. Login and replay endpoints are rate-limited. Successful state-changing
actions are audited — **never** passwords or tokens.

### 6.2 Transaction ingestion

Single and bounded batch ingestion with explicit maximum item and payload sizes. Validation of
identifiers, currency, amount, timestamp, channel, and required references. Zero and negative
amounts rejected unless a separately modelled reversal type permits them. Idempotency keys
accepted and enforced unique. RFC 9457 problem-details error bodies. Correlation ID returned in
headers. **Transaction and outbox record committed atomically** — Kafka is never written before
the primary database write. No claim of global exactly-once processing; the guarantees are
idempotency, unique constraints, processed-event records, and transactional boundaries.

### 6.3 Synthetic scenario replay

Deterministic, seed-based scenarios: normal retail spending · high-value outlier · rapid
velocity · repeated rounded-value transfers · new device with unusual location · balance drain ·
rapid fan-out · rapid fan-in · unusual hour with high amount · duplicate/idempotency test ·
temporary scoring outage · malformed poison event for the dead-letter demonstration.

Every scenario is labelled **synthetic and illustrative** — never presented as a real
institution's fraud rule.

### 6.4 Risk scoring

Transparent rule indicators; a versioned contract to the Python scoring API; timeouts, bounded
retries with jitter, and circuit-breaker behaviour for transient failures; **no retry of
non-retryable validation errors**. Persisted for every decision: rule score, model score, final
score, risk band, model version, feature version, threshold/policy version, reason codes, and
scoring latency. Bands are `LOW` / `MEDIUM` / `HIGH` / `CRITICAL`, configuration-driven and
validated. Repeated processing cannot create duplicate assessments or alerts.

### 6.5 Alert lifecycle

A server-enforced state machine:

```text
NEW → IN_REVIEW → CONFIRMED_SUSPICIOUS → CLOSED
        ↘ ESCALATED
        ↘ DISMISSED_FALSE_POSITIVE → CLOSED
```

Invalid transitions return a clear conflict response. Optimistic locking prevents lost updates.
Every change records actor, timestamp, previous state, new state, sanitized note, and correlation
ID. Auditors remain read-only. Analyst feedback is stored for offline evaluation and **never**
silently retrains or promotes a model.

### 6.6 Live updates

Server-Sent Events with authenticated subscriptions, reconnection handling, and a bounded-polling
fallback. Event payloads expose no confidential or unrestricted fields.

### 6.7 Reporting

Server-side filtered reporting with pagination: transaction volume, risk-band distribution, alert
status, top synthetic reason codes, scoring latency, and false-positive feedback summaries.
Explainable SQL with measured indexes. CSV export is server-side, access-checked, bounded, and
protected against spreadsheet formula injection.

## 7. Non-goals for v1

Eight-plus independently deployed microservices · Kubernetes as a local requirement · any real
payment network or bank integration · real KYC, credit-bureau, sanctions, or identity providers ·
real money movement · automatic account freezing or adverse financial decisions · unreviewed
continuous retraining · deep learning, LLMs, paid embeddings, or GPU dependencies · a graph
database or GNN in the core release · multi-region production infrastructure · any claim of
regulatory compliance, SOC 2, PCI DSS, or AML certification · reproducing any employer's reported
volume, latency, or false-positive metrics.

## 8. Quality requirements

| Area            | Requirement                                                                                                                                                                                                            |
| --------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Reproducibility | Clean clone starts through documented commands; migrations run from an empty database; the data generator and model training are seed-deterministic                                                                    |
| Testing         | Unit, integration (real PostgreSQL and Kafka via Testcontainers), contract, end-to-end, and smoke suites                                                                                                               |
| Coverage        | Targets set after a baseline is measured — indicatively Java ≥80% (critical domain/state/security packages ≥90%), Python ≥85% (feature/scoring logic ≥90%), frontend ≥80%. Coverage is not treated as proof of quality |
| Security        | Threat model, server-side authorization, bounded payloads, secret scanning, CodeQL, dependency review, container scan, SBOM, non-root containers, least-privilege workflows                                            |
| Observability   | Low-cardinality metrics, structured JSON logs with redaction, W3C trace propagation across HTTP and Kafka, provisioned Grafana dashboards, runbooks                                                                    |
| Accessibility   | WCAG 2.2 AA for implemented flows, verified by automated axe checks and documented manual testing                                                                                                                      |
| Performance     | Every number measured on a documented reference environment with stated seed, dataset size, and repetitions. **No invented figures**                                                                                   |

## 9. Performance objectives

Goals until measured — recorded honestly whether met or missed:

- Transaction acceptance API p95 under 300 ms at a documented moderate local load, excluding
  asynchronous completion.
- Scoring-service p95 under 200 ms for a single request on the reference CPU.
- Stable replay of at least 100 synthetic events/second, if the hardware and implementation
  support it.
- No duplicate business result under retry and idempotency tests.
- Report queries responsive on a generated dataset of at least 100,000 transactions.

## 10. Explicitly out of scope until after v1

Two smaller demonstrations, gated on a verified v1 release **and** explicit approval:

- **Synthetic customer onboarding** — application intake, validation, duplicate checks,
  document-metadata placeholders, review status, audit history, onboarding events. Fictional data
  only; no real identity verification, credit checks, or biometrics.
- **MySQL-to-PostgreSQL migration lab** — a reproducible source schema, deterministic sample
  records, Python migration and validation commands, checksums, reconciliation reports, idempotent
  reruns, failure recovery, and an operations runbook. A contained lab, never a second production
  database in the core application.
