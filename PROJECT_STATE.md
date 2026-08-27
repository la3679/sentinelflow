# SentinelFlow Project State

> Authoritative resume file for fresh sessions. Update before every stop, compaction, tool
> handoff, and push checkpoint.

## Resume instructions

1. Read this file completely.
2. Run the verification commands in "Session startup commands".
3. Confirm branch, HEAD, and remote before editing.
4. Continue from "Next three actions"; do not restart completed phases.

## Snapshot

| Field                | Value                                                                                                                                            |
| -------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| Last updated UTC     | 2026-08-26T23:05Z                                                                                                                                |
| Updated by           | Claude                                                                                                                                           |
| Overall status       | active — Phase 4 in progress: the boundary, the data, the contract and the model decision                                                        |
| Current phase        | Phase 4 — synthetic data and scoring (five of fourteen pieces done)                                                                              |
| Current task         | the account-context assembler and the labelled export, then reproducible training                                                                |
| GitHub repository    | <https://github.com/la3679/sentinelflow>                                                                                                         |
| Visibility           | **PUBLIC** since 2026-08-25, after both scans passed                                                                                             |
| Default branch       | `main` — **protected** since 2026-08-25 (ruleset `main protection`, id `21493410`)                                                               |
| Working branch       | `docs/adr-model-and-evaluation`                                                                                                                  |
| Local clone verified | **yes**                                                                                                                                          |
| Local workspace      | a `sentinelflow/` folder inside the user's Documents workspace. The absolute path is recorded in the git-ignored `.claude/runtime/worktree.json` |
| Lovable sync branch  | `main` — **generation retired**, see "Lovable" below                                                                                             |
| Open PRs             | none                                                                                                                                             |
| Latest release       | none                                                                                                                                             |

Local HEAD, remote HEAD, and CI state change every commit and are **not** recorded here. Run
`scripts/claude/checkpoint` (or `.\scripts\claude\checkpoint.ps1`) to read them from the source of
truth rather than from a stale table.

### Lovable

| Field             | Value                                                                    |
| ----------------- | ------------------------------------------------------------------------ |
| Workspace         | `Love's Lovable` (`sPLbx3W6voC6jPB4kPG6`), plan `pro`                    |
| Project           | `SentinelFlow` (`e1341a35-a595-4af4-b0a5-c158ba286897`)                  |
| Editor            | <https://lovable.dev/projects/e1341a35-a595-4af4-b0a5-c158ba286897>      |
| GitHub connection | active — **never rename, transfer, or delete the repository** (ADR-0001) |
| Generation        | **retired in Phase 1** — the console is no longer at the repository root |

Lovable has no documented support for an application outside the repository root, so moving the
console to `apps/web/` ended its ability to regenerate or preview this project. That trade-off was
taken deliberately and is recorded in [ADR-0002](docs/adr/0002-monorepo-and-service-boundaries.md)
and [`docs/operations/LOVABLE_GITHUB_WORKFLOW.md`](docs/operations/LOVABLE_GITHUB_WORKFLOW.md),
which also records the two honest routes back to a design session if one is ever wanted.

## The Actions outage, and how it ended

**GitHub Actions was in a declared `major_outage` from 15:11Z on 2026-08-26.** Runs queued and never
started, two returned `startup_failure` in seconds on workflow files that had been green half an hour
earlier, and `gh run cancel` refused stuck runs as "completed" while the API still reported them
`queued`. Git, the API and Pull Requests were unaffected throughout.

**It cleared the same day**, and everything it had blocked has since landed. Recorded here because
the recovery had one non-obvious step worth keeping:

**The seven stranded runs never recovered and never will.** Actions returning to `operational` did
not revive them — each still reports `queued` through the API while `gh run cancel` refuses it as
"completed". They are permanently stuck. Fresh runs had to be dispatched, and
`gh pr close <n> && gh pr reopen <n>` is what does it, because a `reopened` event re-triggers every
workflow that listens on `pull_request`.

**If this happens again:** check `curl -s https://www.githubstatus.com/api/v2/summary.json` for the
component state rather than inferring it from stuck runs, then close and reopen the pull request once
Actions is operational. Do not chase runner labels or concurrency groups first — both were ruled out
last time, and neither explains a `startup_failure` on an unchanged workflow file.

## Product status by phase

- [x] **Phase 0 — research gate and Lovable/GitHub bootstrap**
- [x] **Phase 1 — monorepo and developer foundation**
- [x] **Phase 2 — contracts, domain, and database**
- [x] **Phase 3 — ingestion, outbox, and Kafka**
- [ ] **Phase 4 — synthetic data and scoring** ← in progress
- [ ] Phase 5 — alerts and investigations
- [ ] Phase 6 — operations frontend
- [ ] Phase 7 — observability and resilience
- [ ] Phase 8 — security and quality hardening
- [ ] Phase 9 — performance and documentation
- [ ] Phase 10 — release

## Completed — Phase 1

**Monorepo.** The console moved from the repository root to `apps/web/`, and `apps/api` and
`apps/scoring` were created alongside it. The root is a Bun workspace holding the single Node
lockfile and repository-wide formatting.

**`apps/api`** — Spring Boot 4.1.1 on Java 25. Maven Wrapper 3.3.4, script-only so no jar enters
the repository, pinned to Maven 3.9.16 with a SHA-256 verified against both Apache's published
SHA-512 and Maven Central. Spotless (palantir-java-format) and JaCoCo at `verify`. Actuator
exposes only health, info and prometheus, with liveness and readiness separate; a test asserts
`/actuator/env` and `/actuator/beans` return 404.

**`apps/scoring`** — FastAPI on Python 3.13 exactly, managed by `uv` with a committed lock. ruff,
`mypy --strict`, and pytest with `filterwarnings = ["error"]`. Configuration is pydantic-settings
with `extra="forbid"`, and a test proves a typo in a variable stops startup.

**Containers.** Multi-stage images for all three, non-root, health-checked, build tools kept out
of runtime. Three defects were found by _running_ them rather than reading them, and are recorded
in the commit messages.

**Local stack.** `compose.yaml` brings up PostgreSQL 18.6, Kafka 4.2.1 in KRaft, the three
applications, Prometheus and Grafana. Health checks probe behaviour rather than ports. Grafana's
Prometheus datasource is provisioned from a file.

**Command surface.** A `Makefile` and a native PowerShell runner, because the reference Windows
machine has no `make`. Four platform-specific defects were found by running them; all are recorded.

**CI.** `ci-repo`, `ci-api`, `ci-scoring`, `ci-web`, `ci-containers`, plus the existing security
scan. Container images are built and Trivy-scanned on every push, and CI asserts the non-root user
against the built image.

**Repository.** Community health files, issue and PR templates, CODEOWNERS, Dependabot across five
ecosystems, the label set, milestones M0–M5, Discussions, secret scanning, push protection,
Dependabot security updates, and a `main` ruleset.

**`.claude/`.** Status line, four hooks, per-language rules, and a checkpoint helper — all verified
against the current schema and exercised locally before commit.

**Documentation.** ADR-0002, `LOVABLE_GITHUB_WORKFLOW.md`, `BRANCH_PROTECTION.md`,
`CLAUDE_CODE_SETUP.md`, four new research entries, and a README rewritten from Lovable's
prompt-dump into something verified, with two generated screenshots.

## In progress — Phase 4

Five pieces have landed. The rest of the phase is the model itself and the client that calls it.

**[ADR-0008](docs/adr/0008-scoring-service-boundary.md), merged as PR
[#29](https://github.com/la3679/sentinelflow/pull/29).** Written before either side of the boundary
existed, because it was about to be decided by whichever line of code was written first. Synchronous
HTTP from inside the consumer's handler rather than an event round trip; scoring unavailable writes a
degraded assessment rather than failing; budgets under ten seconds end to end because a consumer's
retry blocks its partition; and the API owns the alerting threshold while the scoring service owns
the score.

Two gaps in it were found by reading it back against ADR-0002 and closed in the same pull request.
It had settled that the call is HTTP and never said **what is in it** — so the request now carries
the transaction plus a bounded account context the API computes, and the scoring service stays
stateless rather than acquiring a database. And ADR-0002's "evaluation metrics and thresholds"
row read as a contradiction: it is not, because a model's **operating point** and the **alerting
policy** are different objects on different schedules, and both are persisted so which is which is
never inferred.

**The scenario generator, merged as PR
[#30](https://github.com/la3679/sentinelflow/pull/30).** Deterministic synthetic traffic over the
seeded parties: ordinary background spending with six shapes planted in it — a velocity burst, an
amount spike relative to that account's own baseline, a card-testing run, an improbable journey, an
account drain proportional to the balance, and an off-hours purchase from a device the account has
never used. Every shape needs history to see, which is the point of generating data at all.

It writes through `TransactionWriter`, so generated traffic gets the same validation, the same
idempotency constraint and the same outbox row as a posted transaction, and flows through the relay
and the consumer exactly as real traffic does. `make seed` is implemented on both the Makefile and
the PowerShell runner.

**Labels never enter the database**, and `ScenarioLoaderIT` asserts it against `information_schema`
rather than trusting the intent. The distribution lives in the manifest as counts.

**The feature pipeline, merged as PR
[#34](https://github.com/la3679/sentinelflow/pull/34).** Sixteen versioned, deterministic features
over the transaction and the account context, with the request models and a suite that keeps them
from drifting from the contract.

**Leakage is prevented structurally rather than by care.** Every window is measured backwards from
the transaction's own `occurredAt`, and anything at or after it is discarded before a feature sees
it — because the API sends history as of when it _asked_, so a replayed or late-arriving transaction
legitimately carries history from after itself. A leak makes every metric look better, which is why
an assertion is the only thing that ever catches one.

Every default was chosen against the version that would have made an account's first transaction its
most alarming: no history is a ratio of 1.0 rather than something enormous, a null device on an ATM
is a real answer rather than a new device, and a drain against a zero balance is 0.0 rather than
infinity. `lookbackWindowSeconds` and `truncated` are honoured rather than accepted and ignored.

`apps/scoring` gained its first coverage floor — measured at 95.9%, set to 90.

**The scoring contract, merged as PR
[#31](https://github.com/la3679/sentinelflow/pull/31).** `contracts/openapi/sentinelflow-scoring.yaml`
— `/v1/score`, `/v1/model`, and the health endpoints — written before either implementation, the same
order the public API document was written in. `check-contracts.mjs` now validates **every** document
in `contracts/openapi/` rather than one named file, which is what stops a second authoritative
document from being one nothing checks.

### Two defects found by running things

- **Hibernate logged every aborted statement at WARN with the full SQL and all bound values.** A
  duplicate idempotency key is normal traffic under at-least-once ingestion — handled, with the
  caller getting its original result back — and it printed the amount, both references, the device
  handle and the key. Wrong twice: it turns the expected path into an alarm, and those values are
  what this project's own rules say not to log. `org.hibernate.orm.jdbc.error` is now at ERROR.
- **A skip in my own test made twelve assertions vanish.** The contract-conformance suite guarded
  its fixture with a skip when the contract file was not found, and the path was wrong by one level,
  so twelve tests reported as skipped and the run passed green. A skip that fires because of a defect
  is indistinguishable from one that fires legitimately, and there is no legitimate absence here. It
  asserts now.
- **Content-derived idempotency keys collide.** Two ordinary purchases on one account, at one
  merchant, in the same second, for the same amount are possible in fourteen days of traffic, and the
  second would have been silently rejected as a duplicate — leaving a dataset smaller than the
  manifest claimed. A sequence number fixes it, and a test asserts uniqueness over the `DEMO` profile.

**[ADR-0010](docs/adr/0010-model-selection-and-evaluation.md), the model and evaluation choice.**
Written before the training code, and it changed the order of the rest of the phase.

**Labels have exactly one home, and it is not the database.** `ScenarioType` already said planted
shapes never enter the schema, which leaves the training source undecided rather than obvious. An
offline export in `apps/api` writes the exact `ScoreRequest` body the service would receive plus the
planted label — so the generator is not reimplemented in Python, where two definitions of six shapes
would drift and the drift would look like a modelling problem.

**The export must call the runtime's own account-context assembler.** This is the part that reorders
the phase. All sixteen features are computed from that context, so an assembler that windowed,
ordered, capped or truncated differently at training time produces train/serve skew that **no metric
in the evaluation report can detect** — both sides of the comparison would come from the training
assembler. The assembler therefore moves ahead of training, and the Spring client consumes it
instead of writing a second one.

**The score is a calibrated probability, which follows from ADR-0008 §4 rather than from taste.** One
threshold, owned by the API, has to mean the same thing under a model and under a rules-only degraded
assessment. That rules `IsolationForest` out of production before anything is trained — its anomaly
score is unbounded and dataset-relative, and calibrating it against labels defeats the reason it was
included. It stays as an unsupervised comparison.

**The selection rule is fixed in advance so it cannot be rationalised afterwards.** PR-AUC is the
headline and accuracy is never one; a model ships only if it beats the rules baseline by a stated
margin, and **the rules ship alone if none does**; a gap inside the cross-validation fold spread is
fold noise and goes to logistic regression; and the operating point is chosen against an
alert-volume budget rather than by maximising F1, because an analyst team is a fixed-capacity queue.

### What remains in Phase 4

| Piece                                       | State                                                    |
| ------------------------------------------- | -------------------------------------------------------- |
| ADR-0008, the scoring boundary              | **done** (#29)                                           |
| Scenario generator, `make seed`             | **done** (#30)                                           |
| Scoring contract                            | **done** (#31)                                           |
| Versioned feature pipeline                  | **done** (#34)                                           |
| ADR-0010, model and evaluation choice       | **done**                                                 |
| Account-context assembler                   | not started — **moved ahead of training**, ADR-0010 §1   |
| Labelled dataset export                     | not started — `apps/api`, offline, never persisted       |
| Transparent rules baseline                  | not started — `apps/api`, per ADR-0002                   |
| Reproducible training, evaluation, registry | not started                                              |
| Model card and `EVALUATION.md`              | not started                                              |
| `/v1/score` and `/v1/model` implementations | not started                                              |
| Spring scoring client with resilience       | not started — consumes the assembler, does not write one |
| Persisted risk assessments                  | not started                                              |
| `make replay`                               | not started — lands with the client, see below           |

**`make replay` is deliberately still unimplemented and still fails loudly.** The transaction shapes
it would replay are generated today by `make seed`. Its own value is in the operational scenarios
§8.3 lists — a temporary scoring-service outage, a malformed event reaching the dead-letter path —
and neither exists to replay until the scoring client does. It lands with the pieces it demonstrates
rather than ahead of them.

**Rapid fan-in is not expressible at all**, and `docs/data/DATA_PROVENANCE.md` says so rather than
leaving it as a silent gap: `transactions` records an account and a merchant and has no counterparty
account column, so a transfer between two accounts is one row on one account. Changing the schema to
satisfy a generator would be the wrong way round. Rounded-value transfers and rapid fan-out are
expressible and are simply not implemented yet.

## Completed — Phase 3, merged as PRs [#26](https://github.com/la3679/sentinelflow/pull/26), [#27](https://github.com/la3679/sentinelflow/pull/27) and [#28](https://github.com/la3679/sentinelflow/pull/28)

**The pipeline runs end to end.** A transaction posted to the API is written with its outbox row in
one transaction, published to Kafka by the relay, consumed idempotently, and either handled or
dead-lettered with a classified reason. Everything below was executed on 2026-08-26 against real
PostgreSQL 18.6 and real Kafka 4.2.1 in Testcontainers, and reproduced on the GitHub runner.

**Ingestion (#26).** Single and bounded batch ingestion with Bean Validation at the boundary, RFC
9457 problem details, and idempotency that the database enforces rather than the application. The
pre-insert lookup is an optimisation; `transactions_idempotency_unique` is the guarantee, and the
service is written to lose that race gracefully rather than to pretend it cannot happen. Three
outcomes stay distinct: 202 created, 200 replayed, 409 for a key reused with a different payload.

**The relay (#27).** Polling with `FOR UPDATE SKIP LOCKED`, claim and publish and status update in
one transaction, bounded retry with full jitter, `FAILED` terminal. Three gauges — pending depth,
failed depth, and the age of the oldest unpublished event — because depth alone cannot tell a busy
relay from a stuck one.

**The consumer (#28).** The other half of the at-least-once bargain:

- **The ledger row and the effect are one transaction, row first.** The claim is
  `INSERT ... ON CONFLICT DO NOTHING`, so a second delivery does nothing; if the effect throws, the
  rollback takes the claim with it and the retry is genuinely a first attempt. Not exception-driven,
  because a constraint violation marks a PostgreSQL transaction rollback-only and the ordinary
  duplicate case could then not commit.
- **Failures are classified rather than retried indiscriminately.** An unreadable envelope, an
  unknown `eventType`, an unsupported `schemaVersion` and a payload of the wrong shape go straight to
  `transaction.processing.dlq.v1` after one attempt. A handler's exception is retried on a bounded,
  fully-jittered schedule and dead-lettered as `RETRY_EXHAUSTED` when the budget runs out.
- **Retries block the partition on purpose.** A non-blocking retry topic would free it at the cost of
  the per-account ordering ADR-0006 §2 keys these events by, which velocity rules depend on. The
  budget is deliberately an order of magnitude shorter than the relay's: the relay waits for a broker
  holding one row, a consumer holds up everything queued behind it.
- **`FullJitterBackOff` is written rather than configured.** Spring's `ExponentialBackOff` grew a
  `jitter` property that perturbs the interval by plus-or-minus jitter; ADR-0006 §4 asks for a
  uniform draw across the whole window. Different distributions, one name — a test asserts which one
  this is.
- **Dead-lettering a transaction event marks the transaction `FAILED`**, meaning the pipeline will
  not assess it rather than that it was rejected. Left `PENDING` it would wait for something that is
  not coming.
- **No `TransactionCreatedHandler` implementation ships.** Scoring is Phase 4 and is the first thing
  that will genuinely act on one. The consumer injects a `List` and dispatches to every
  implementation, so Phase 4 adds a bean rather than editing the consumer, and the tests register
  their own handler through that same seam. A no-op implementation would be dead code pretending to
  be a feature.

### The one thing the DLQ deliberately cannot hold

`dlq-record.v1.json` requires `originalEvent` to be a complete valid envelope, and ADR-0006 §4
forbids copying an unsanitised payload fragment onto a topic operations staff read. A message that is
not an envelope at all therefore has no legitimate representation in a dead-letter record.

Relaxing the schema was considered and rejected — it would carve an exception into an accepted ADR
quietly. Instead such a message is logged at `ERROR` with its exact topic, partition and offset,
counted under `sentinelflow_consumer_undeliverable_total`, and its offset committed. The original
bytes stay readable at those coordinates for as long as retention holds them, and the partition does
not stop. An integration test publishes malformed JSON ahead of a valid event and asserts the valid
one is still handled.

### Defects found by running, not reading

- **The `@KafkaListener` broke twenty-three existing tests.** A listener container whose bootstrap
  address does not resolve fails the whole application context at startup rather than retrying, so
  every Postgres-only suite failed with `No resolvable bootstrap urls`. Fixed with
  `sentinelflow.consumer.enabled`, mirroring the relay's flag: on by default, off in
  `AbstractPostgresTest`.
- **A `@TestConfiguration` implementing the interface it provides is injected twice**, so every
  delivery would have been counted twice. The configuration and the handler are separate classes now.
- Earlier in the phase: Jackson coercing a JSON number into the money `String` field;
  `default-property-inclusion: non_null` dropping a required nullable field from an event payload;
  `spring-boot-kafka` missing exactly as `spring-boot-flyway` had been; `KafkaTemplate.send` throwing
  synchronously rather than returning a failed future; and a unit test reading `../../contracts`,
  which cannot work inside the module-only Docker context where CI runs the unit suite.

## Acceptance criteria status — Phase 3 gate

| Criterion                                     | Status   | Evidence                                                                         |
| --------------------------------------------- | -------- | -------------------------------------------------------------------------------- |
| Duplicate submission cannot duplicate data    | **pass** | `TransactionIngestionIT`; 8 threads race one key, one transaction and one event  |
| Event survives temporary Kafka unavailability | **pass** | `OutboxRelayIT` — publication fails, row stays `PENDING`, retried, never lost    |
| Consumer retry and DLQ tests pass             | **pass** | `TransactionCreatedConsumerIT`, 9 tests against a real broker                    |
| Duplicate delivery cannot duplicate an effect | **pass** | `IdempotentEventProcessorIT`, 5 tests including an 8-thread race                 |
| Trace and correlation evidence exists         | **pass** | `correlationId` on the envelope, the outbox row and the MDC while a handler runs |
| Metrics baseline                              | **pass** | 3 outbox gauges, 3 counters; every one named in `docs/operations/RUNBOOKS.md`    |
| Integration tests with Kafka and PostgreSQL   | **pass** | Testcontainers throughout; nothing in the messaging suites is mocked             |

**CI green, verified.** All ten required checks passed on #26, #27 and #28, and the api job ran the
Testcontainers suites on the runner rather than merely compiling them — its log shows 41 unit and 107
integration tests and the coverage gate met. This is not a claim resting on one machine.

## Completed — Phase 2, merged as PR [#21](https://github.com/la3679/sentinelflow/pull/21)

**The schema now runs.** Everything below was executed on 2026-08-26 against real PostgreSQL 18.6
in Testcontainers, under `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1`.

**Testcontainers foundation.** One `postgres:18.6-alpine` container per JVM fork, exposed as a
`@ServiceConnection` bean, image name from the `postgres.test.image` pom property. Flyway applies
all six migrations to an empty database and Hibernate validates every mapping against the result;
both are assertions in themselves.

**All fifteen tables mapped**, and `ddl-auto: validate` accepts every one. Foreign keys are `UUID`
fields rather than `@ManyToOne`, so no traversal is an implicit query. `TransactionRecord` is named
around the collision with `jakarta.transaction.Transaction`. Invalid states are unconstructible, not
merely constrained: `RiskAssessment` has `scored()` and `degraded()` factories and no public
constructor, `Alert.transitionTo` sets `closedAt` from the target status, `OutboxEvent` derives its
aggregate type from its event type, and `AuditLogEntry.byUser` requires the actor the `CHECK`
requires.

**Three defects, all found by running rather than reading.** `PostgreSQLContainer<?>` does not
compile against Testcontainers 2.x. `spring.datasource.hikari.connection-timeout: 10s` failed
startup with `NumberFormatException`, because that prefix binds onto `HikariDataSource` whose
setters take a `long` — committed, unrun, and would have failed identically in production. And
`spring-boot-flyway`, a separate module in Spring Boot 4, was missing: every `spring.flyway.*`
property bound to nothing, no migration ran, and the only symptom was Hibernate reporting a missing
table, which reads like a mapping defect and is not one.

**Constraint, migration and mapping suites — 57 integration tests.** Every constraint test names the
constraint it expects to fire, and where a rule has a permitted counterpart the counterpart is
asserted too. `MigrationIT` covers what a constraint test cannot reach: the migration history, the
PostgreSQL version `uuidv7()` needs, that no money column is floating point, that no timestamp lost
its zone, and that the six deliberately-chosen indexes still exist and the partial ones are still
partial.

**Domain unit tests — 23.** `Money` and `UuidV7` had none. The UUIDv7 tests pin the clock, because a
generator is trivially ordered when time passes between calls and index locality matters under a
burst.

**`Alert.version` resolved.** OpenAPI moved to `minimum: 0` with the reason stated in the schema;
`alert-updated.v1.json` keeps `minimum: 1` and now says why.

**Seed foundation.** Deterministic, idempotent, off by default everywhere, application code and
never a migration. Parties only — customers, accounts, merchants, four fixed analyst logins; the
scenario generator and `make seed` remain Phase 4. A `SeedManifest` carries the generator version,
seed, profile, counts and a SHA-256 over the generated references.
[`docs/data/DATA_PROVENANCE.md`](docs/data/DATA_PROVENANCE.md) records §13.5.

**Diagrams.** [`docs/architecture/DATA_MODEL.md`](docs/architecture/DATA_MODEL.md) and
[`docs/architecture/TRANSACTION_TO_ALERT.md`](docs/architecture/TRANSACTION_TO_ALERT.md), drawn from
`information_schema` on a database built by the migrations. `SchemaDocumentationIT` asserts the ER
diagram's entity blocks are exactly the tables that exist.

**Coverage gate.** LINE 0.50, BRANCH 0.40 — measured first at 52.4% / 46.9%, then set below the
measurement. `make test-api` is now the unit half and skips JaCoCo; `make test-integration` is
implemented; CI runs the full verify and enforces the gate.

### Correction: the state file was wrong about the entity count

This file and commit `78290dc` both claimed six of fifteen tables were mapped. **Four were.**
`Merchant` and `Account` were described in that commit's message and never written. Git was right,
the state file was wrong, and the discrepancy is recorded in
[`docs/planning/SESSION_LOG.md`](docs/planning/SESSION_LOG.md) as the workflow rules require.

## Acceptance criteria status — Phase 2 gate

| Criterion                          | Status   | Evidence                                                                    |
| ---------------------------------- | -------- | --------------------------------------------------------------------------- |
| Migrations run from empty database | **pass** | Flyway applied 6/6 to `postgres:18.6-alpine`; `MigrationIT` asserts history |
| Constraints tested                 | **pass** | `SchemaConstraintIT`, 29 tests, each naming the constraint it expects       |
| Contracts validate in CI           | **pass** | `check-contracts.mjs` passes; `ci-repo` runs it on every push and PR        |
| Docs match schema                  | **pass** | `SchemaDocumentationIT` asserts the ER diagram against `information_schema` |
| OpenAPI / AsyncAPI / event schemas | **pass** | Delivered in the previous session, amended here for `Alert.version`         |
| Domain model                       | **pass** | 15/15 tables mapped, `ddl-auto: validate` accepts all of them               |
| Seed framework                     | **pass** | `DeterministicSeedLoader`, 6 tests; scenario generator is Phase 4           |
| ER / data diagrams                 | **pass** | `docs/architecture/`, both generated from the live schema                   |

**CI green, verified.** PR [#21](https://github.com/la3679/sentinelflow/pull/21) merged on
2026-08-26 with all ten required checks passing, and all six workflows passed again on `main` at
`c38934f`. The GitHub runner ran the Testcontainers suites for real — its log shows the six
migrations applied and 23 unit plus 57 integration tests — so this is not a claim resting on one
machine.

**One thing blocked the merge and was fixed separately.** Trivy began failing the scoring and web
image scans on **CVE-2026-14456** (OpenSSL, unbounded memory growth in the QUIC server path). Both
are required checks, so it blocked every pull request in the repository, and it was nothing to do
with Phase 2 — `main` was green the day before and the advisory is newer. The base images could not
be bumped because neither had been rebuilt with the fix, so PR
[#22](https://github.com/la3679/sentinelflow/pull/22) takes the patched package from each
distribution at build time, pinned, with a recorded condition for removing the block once the base
images catch up. Not a `.trivyignore`: the vulnerability is genuinely patched and genuinely
available.

## Acceptance criteria status — Phase 1 gate

| Criterion                           | Status   | Evidence                                                                        |
| ----------------------------------- | -------- | ------------------------------------------------------------------------------- |
| Clean-clone bootstrap works         | **pass** | Fresh clone of `55efe6a`: bootstrap ok, frozen install ok, all three apps built |
| Each app builds                     | **pass** | api `BUILD SUCCESS` · scoring `uv sync --frozen` · web `vite build` exit 0      |
| Each app has a health or smoke test | **pass** | api 5/5 · scoring 6/6 · web 24/24 unit + 58/58 browser · stack smoke 23/23      |
| CI green                            | **pass** | All six workflows passing on the branch head                                    |
| ADR for the structural decision     | **pass** | ADR-0002                                                                        |
| `main` protected                    | **pass** | Ruleset `21493410`, verified through the rules API                              |

## Test and verification evidence

Every figure below came from a run on the date its section names. Nothing here is estimated.

### 2026-08-26 — Phase 4, so far

Local run under `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1`, then reproduced on the GitHub runner.

| Command                                  | Result                                                        |
| ---------------------------------------- | ------------------------------------------------------------- |
| `./mvnw verify` (JDK 25.0.4.1+1)         | **PASS** — 57 unit, 116 integration, coverage gate met        |
| The same, on the GitHub runner           | **PASS** — same counts, gate met                              |
| JaCoCo over both suites                  | 80.5% lines (1168/1451), 70.0% branches (191/273)             |
| `bun scripts/dev/check-contracts.mjs`    | **PASS** — all three API documents                            |
| The same, against a broken document      | **FAILS and names the file** — verified, not assumed          |
| `bun scripts/dev/check-docs.mjs`         | **PASS** — 99 links across 35 files, 0 broken, 0 placeholders |
| `bun run format:check` (repository-wide) | **PASS**                                                      |
| PowerShell parse of `sf.ps1`             | **PASS** — 0 errors                                           |
| `uv run pytest` (scoring)                | **PASS** — 42 passed                                          |
| `uv run pytest --cov` (scoring)          | **95.88%**, gate of 90 met; both feature modules 100%         |
| `uv run mypy` (scoring, strict)          | **PASS** — 0 issues, 12 files                                 |
| `uv run ruff check` / `format --check`   | **PASS** — 13 files                                           |

The coverage gate stays at LINE 0.70 / BRANCH 0.60. It measures above both, and ratcheting twice
inside one phase is churn; the next turn comes when Phase 4 finishes.

### 2026-08-26 — Phase 3

Local run under `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1`, then reproduced on the GitHub runner.

| Command                                  | Result                                                        |
| ---------------------------------------- | ------------------------------------------------------------- |
| `./mvnw verify` (JDK 25.0.4.1+1)         | **PASS** — 41 unit, 107 integration, coverage gate met        |
| The same, on the GitHub runner           | **PASS** — 41 and 107, same counts, gate met                  |
| JaCoCo over both suites                  | 77.6% lines (944/1216), 66.1% branches (144/218)              |
| `bun scripts/dev/check-contracts.mjs`    | **PASS**                                                      |
| `bun scripts/dev/check-docs.mjs`         | **PASS** — 98 links across 34 files, 0 broken, 0 placeholders |
| `bun run format:check` (repository-wide) | **PASS**                                                      |
| `bun install --frozen-lockfile`          | **PASS** on every Dependabot branch after regeneration        |

The coverage gate was ratcheted from LINE 0.50 / BRANCH 0.40 to **0.70 / 0.60** — measured first,
then set below the measurement, as the previous turn of the ratchet was.

### 2026-08-26 — Phase 2

| Command                                      | Result                                                        |
| -------------------------------------------- | ------------------------------------------------------------- |
| `./mvnw verify` (JDK 25.0.4.1+1)             | **PASS** — 23 unit, 57 integration, coverage check met        |
| `./mvnw verify -DskipITs -Djacoco.skip=true` | **PASS** — 23/23, no Docker needed                            |
| JaCoCo over both suites                      | 62.0% lines (432/697), 63.8% branches (60/94)                 |
| Flyway against `postgres:18.6-alpine`        | **PASS** — 6/6 migrations applied to an empty database        |
| Hibernate `ddl-auto: validate`               | **PASS** — all 15 mappings accepted                           |
| `bun scripts/dev/check-contracts.mjs`        | **PASS**                                                      |
| `bun scripts/dev/check-docs.mjs`             | **PASS** — 89 links across 33 files, 0 broken, 0 placeholders |
| `bun run format:check` (repository-wide)     | **PASS**                                                      |
| `bun run typecheck` (web)                    | **PASS**                                                      |
| `bun run test` (web)                         | **PASS** — 24/24                                              |

### 2026-08-25 — Phase 1

| Command                                  | Result                                                              |
| ---------------------------------------- | ------------------------------------------------------------------- |
| `bun run format:check` (repository-wide) | **PASS**                                                            |
| `bun scripts/dev/check-docs.mjs`         | **PASS** — 66 links across 27 files, 0 broken, 0 placeholders       |
| `bun run lint` (web)                     | **PASS** — 0 errors, 7 warnings (vendored shadcn/ui fast-refresh)   |
| `bun run typecheck` (web)                | **PASS** (exit 0)                                                   |
| `bun run test` (web)                     | **PASS** — 24/24 across 5 files                                     |
| `bun run test:coverage` (web)            | 40.4% lines (198/490) — routes covered by the browser suite instead |
| `bun run test:e2e` (web)                 | **PASS** — 58/58 (29 desktop + 29 tablet)                           |
| axe WCAG 2.1 A/AA                        | **PASS** — 0 violations, 8 routes, 2 viewports                      |
| `bun run build` (web)                    | **PASS** (exit 0)                                                   |
| `./mvnw verify` (api)                    | **PASS** — 5/5, Spotless clean, `BUILD SUCCESS`                     |
| `uv run ruff check .` (scoring)          | **PASS**                                                            |
| `uv run ruff format --check .` (scoring) | **PASS** — 7 files                                                  |
| `uv run mypy` (scoring, strict)          | **PASS** — 0 issues, 6 files                                        |
| `uv run pytest --cov` (scoring)          | **PASS** — 6/6, 83% lines                                           |
| `docker compose up -d --wait`            | **PASS** — 7/7 healthy                                              |
| `./scripts/smoke/smoke.sh`               | **PASS** — 23/23                                                    |
| `sf.ps1 smoke` (PowerShell 5.1)          | **PASS** — 23/23, identical result                                  |
| Trivy on all three images                | **PASS** — 0 fixable HIGH or CRITICAL                               |
| gitleaks over full history               | **PASS** — 0 leaks                                                  |
| Clean clone → bootstrap → build → test   | **PASS** — all three applications                                   |

Container sizes, measured: api 488 MB · scoring 233 MB · web 83 MB.

**No latency, throughput, false-positive, or model-accuracy figure has been claimed anywhere in
this repository. None has been measured.** Phase 9 measures them.

## Architecture and decisions

**Accepted ADRs**

| ADR  | Decision                                                                                            |
| ---- | --------------------------------------------------------------------------------------------------- |
| 0001 | Lovable creates the GitHub repository; never rename, transfer, or delete it                         |
| 0002 | One monorepo, `apps/{api,scoring,web}`, two services, CI not path-filtered                          |
| 0003 | Java 25 LTS + Spring Boot 4.1.1; dependency versions inherited from the BOM                         |
| 0004 | Python 3.13 via uv for `apps/scoring`                                                               |
| 0005 | Outbox relay: polling with `FOR UPDATE SKIP LOCKED`, jittered bounded retry, `FAILED` is terminal   |
| 0006 | Event envelope, five business topics, account-keyed ordering, at-least-once with an outbox          |
| 0007 | Decimal money as JSON strings, UUIDv7 keys, `timestamptz`, forward-only Flyway migrations           |
| 0008 | Scoring over HTTP from the consumer; degrade on outage; the API owns the alerting threshold         |
| 0009 | Adopt Lovable's TanStack Start foundation; render client-side so Spring Boot stays the sole backend |

**Still needing an ADR:** 0010 model and evaluation choice · 0011 SSE versus WebSockets · 0012
authentication · 0013 observability · 0014 deployment strategy.

**Contracts:** `contracts/` is validated in CI — OpenAPI 3.1 for the public `/api/v1`, OpenAPI 3.1
for the internal API-to-scoring boundary, AsyncAPI 3.0 for the five topics, and seven JSON Schemas.
`make contracts-check` compiles every schema, validates every example, asserts the
deliberately-invalid ones are rejected, and parses **every** document in `contracts/openapi/` rather
than one named file — which is what stops a second authoritative document from being one nothing
checks.

## Known issues and technical debt

- **Node 22.19.0 on the reference machine** passed its LTS end date (2026-07-28). `engines`
  requires Node 24. Bun runs everything, so nothing is blocked, but local Node should be upgraded.
- **Default `JAVA_HOME` points at JDK 17.** JDK 25 is at `~/.jdks/jdk-25.0.4.1+1`;
  `make bootstrap` warns rather than fails, because `make up` builds the API in Docker. Hit
  directly on 2026-08-26: `./mvnw compile` fails with `release version 25 not supported` unless
  `JAVA_HOME` is set for the command. Spotless passes regardless, because formatting does not
  compile — a green formatter is not a green build.
- **Docker Desktop does not start quickly on the reference machine.** Start it before any session
  that touches persistence: every suite except the 41 unit tests needs it, and `make test-api`
  exists precisely so the fast half can run without it.
- ~~**`Alert.version` disagrees between the contract and the mapping.**~~ **Resolved 2026-08-26.**
  OpenAPI moved to `minimum: 0` with the reason written into the schema; `alert-updated.v1.json`
  keeps `minimum: 1` and now says why.
- **The published Temurin 25 image is one critical-patch build behind the local JDK.** Containers
  build on `25.0.4+7`, local `./mvnw` runs on `25.0.4.1+1`. Both are Java 25 LTS. Revisit when
  Adoptium publishes `25.0.4.1`.
- **`noUnusedLocals` / `noUnusedParameters` are still `false`** in `apps/web/tsconfig.json`.
- **Coverage thresholds are enforced in `apps/api` only** — LINE 0.70, BRANCH 0.60, ratcheted on
  2026-08-26 from 0.50/0.40 after Phase 3 measured 77.6% and 66.1%. `apps/scoring` gained its own
  floor with the feature pipeline — `fail_under = 90`, measured at 95.9%. Both are ratchets: raised
  only when a change genuinely raises coverage, never lowered to go green. **`apps/web` still has
  none**; it gets one in Phase 6.
- **Google Fonts is loaded from a remote host.** Self-host in Phase 6 so the local-first stack has
  no external runtime dependency.
- **Screen-reader behaviour is unverified.** axe is not a substitute for a manual pass. Phase 6.
- **The console still renders from `src/mocks/`.** Replaced in Phase 6, once the API has endpoints.
- **CI is not path-filtered**, so every push runs every component. Deliberate (ADR-0002); revisit
  when runtime justifies job-level change detection.
- **Two Dockerfiles pin an OpenSSL package version by hand** (`apps/scoring`, `apps/web`), to take
  the CVE-2026-14456 fix from the distribution before the base images ship it. Both blocks say how
  to check whether the base image has caught up and should be deleted when it has. If a distribution
  rotates the pinned version out of its archive first, the build fails there with a clear message
  and the fix is the same deletion.
- **`apps/web` lint reports 23 warnings, not the 7 this file used to record.**
  `eslint-plugin-react-refresh` 0.4.26 to 0.5.4 — an earlier grouped Dependabot bump, already on
  `main` — flags a TanStack Start route file's `Route` export that `allowConstantExport` used to
  cover. Zero errors, so nothing is blocked, and it is **not** caused by the ESLint 10 bump, which
  was the obvious and wrong suspect. The fix is `allowExportNames: ["Route"]` in
  `eslint.config.js`; Phase 6 owns that file.
- **`RetryStateTracker` measures the failures one process saw.** `attemptCount` and `firstFailedAt`
  in a dead-letter record are captured from the listener's own retry callbacks, so a restart or a
  rebalance mid-retry restarts the clock. They undercount in that case rather than overcounting, and
  the Javadoc and `RUNBOOKS.md` both say so.
- **A message that is not a readable envelope is never dead-lettered.** Deliberate, and recorded in
  the Phase 3 section above and in `DeadLetterRecoverer`'s Javadoc: the DLQ schema requires a valid
  envelope, and ADR-0006 §4 forbids copying unsanitised content onto an operational topic. It is
  logged with its coordinates and counted under `sentinelflow_consumer_undeliverable_total`.
- **Reprocessing a dead-lettered event and reviving a `FAILED` outbox row are both manual.**
  ADR-0005 §5 makes each an administrator-only, audited operation, and the endpoint that exposes it
  is Phase 5 work. Until then `RUNBOOKS.md` describes doing it by hand and says to record who did it.
- **`make` is not installed on the reference machine**, so Makefile targets are exercised there
  through `scripts/dev/sf.ps1` or by running the underlying command directly. Both are changed
  together, every time; a Makefile edit without the matching runner edit is a defect.
- **`AuditLogEntry`, `RegisteredModel`, `AlertAction`, `Role`, `User` and `UserRole` have
  mappings and no callers.** They are validated against the schema and otherwise untouched, which
  is most of the remaining coverage gap. Phases 4 and 5 reach them. `ProcessedEvent` left this
  list in Phase 3.

## Dependabot

**All four major dev-dependency bumps are merged**, on 2026-08-26: `@types/node` 22 to 26 (#9),
`globals` 15 to 17 (#10), `@vitejs/plugin-react` 5 to 6 (#11), and `eslint` 9 to 10 (#12). Each was
kept out of the grouped minor/patch pull request deliberately so it got its own review and its own
CI run, and each was verified locally — typecheck, build, lint and unit tests — before being pushed.

**The lockfile workflow has a gap worth knowing before the next batch.**
`dependabot-bun-lockfile.yml` regenerates `bun.lock`, because Dependabot does not do it for a Bun
workspace. Its header documents the known limitation that a push made with the default
`GITHUB_TOKEN` cannot start further workflow runs, with `gh pr close <n> && gh pr reopen <n>` as the
remedy.

That remedy re-triggers CI. **It does not re-trigger the lockfile job**, because the job is gated on
`github.actor == 'dependabot[bot]'` and a human reopening the pull request is the actor. So a
Dependabot pull request whose lockfile was never regenerated will, on reopen, dispatch every workflow
and skip the one that would have fixed them — reporting `skipping`, which reads like success. Hit on
#11. The fix is to regenerate `bun.lock` by hand and push, which also starts CI, since the push is
made with a real account.

Three earlier Dependabot pull requests were **closed with reasons** rather than merged, because
each would have broken a recorded decision: Temurin 25 to 26 (not an LTS, ADR-0003), Python
3.13 to 3.14 (joblib, ADR-0004), and nginx 1.30 stable to 1.31 mainline (verified by digest).
`.github/dependabot.yml` now carries ignore rules naming each decision, so none will be proposed
again.

## Blockers and required user input

None.

## Next three actions

Phase 4 is in progress and `main` is green. Nothing is blocked. ADR-0010 is decided, and it moved
the account-context assembler ahead of training — that reorder is what item 1 is.

1. **The account-context assembler in `apps/api`, and the labelled export built on it.** One
   implementation, used by the runtime scoring call and by training, per ADR-0010 §1: it computes
   the bounded history ADR-0008 fixes as what crosses the boundary — the lookback window, the
   newest-first ordering, the 200-row cap and the `truncated` flag the contract already declares.
   The export then runs `ScenarioGenerator` and writes one JSONL record per transaction — the exact
   `ScoreRequest` body plus the planted `ScenarioType`. **The label goes in that file and nowhere
   else**; `ScenarioLoaderIT` already asserts against `information_schema` that no label column
   exists, and that assertion stays true.
2. **Reproducible training, evaluation, and the model registry.** An explicit offline command, never
   an API side effect. Save the dataset fingerprint, the feature version, the split strategy, the
   seeds, the hyperparameters, the environment lock, the metrics JSON, the plots, the artifact
   checksum and the model card. Split group-disjoint on account **and** time-ordered (ADR-0010 §3);
   calibrate, and report Brier and a reliability curve (§4). **Accuracy is never the headline** under
   this imbalance — PR-AUC is, with precision, recall, false-positive rate and alert volume at the
   budgeted operating point beside it. `docs/ml/MODEL_CARD.md` and `docs/ml/EVALUATION.md` ship with
   the model, not after it. If nothing beats the rules baseline by the stated margin, the rules ship
   alone and that outcome is recorded rather than worked around.
3. **`/v1/score` and `/v1/model`, then the rules baseline, the Spring client and persisted
   assessments.** The rules baseline lives in `apps/api` (ADR-0002), which is what makes a degraded
   assessment a real answer rather than a null with a flag on it. The client carries the timeouts,
   bounded retry and circuit breaker ADR-0008 §3 fixes; the breaker is load-bearing, because without
   it every record in a backlog pays the full timeout before degrading. It **consumes the assembler
   from item 1** rather than writing a second one. The first real `TransactionCreatedHandler`
   registers into the list the consumer already injects, so the consumer needs no change.
   `make replay` lands here, since a scoring outage and a poison event are the scenarios worth
   replaying.

## Session startup commands

```text
cd <clone root>
git rev-parse --show-toplevel
git remote -v
git branch --show-current
git status --short --branch
git fetch --all --prune
scripts/claude/checkpoint
bun install --frozen-lockfile
make bootstrap
```

## Safe resume prompt

> Read CLAUDE.md and PROJECT_STATE.md, verify Git and GitHub state with
> `scripts/claude/checkpoint`, then continue the first incomplete item in "Next three actions"
> without repeating completed work.
