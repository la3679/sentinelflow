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
| Last updated UTC     | 2026-08-26T14:05Z                                                                                                                                |
| Updated by           | Claude                                                                                                                                           |
| Overall status       | active — Phase 2 deliverables complete and **executed** on `feat/domain-and-migrations`; PR next                                                 |
| Current phase        | Phase 3 — ingestion, outbox, and Kafka (not started)                                                                                             |
| Current task         | Phase 2 — open the pull request, get CI green on it, merge                                                                                       |
| GitHub repository    | <https://github.com/la3679/sentinelflow>                                                                                                         |
| Visibility           | **PUBLIC** since 2026-08-25, after both scans passed                                                                                             |
| Default branch       | `main` — **protected** since 2026-08-25 (ruleset `main protection`, id `21493410`)                                                               |
| Working branch       | `feat/domain-and-migrations` — branched from `4de1ff8`, eleven commits, **no PR yet**                                                            |
| Local clone verified | **yes**                                                                                                                                          |
| Local workspace      | a `sentinelflow/` folder inside the user's Documents workspace. The absolute path is recorded in the git-ignored `.claude/runtime/worktree.json` |
| Lovable sync branch  | `main` — **generation retired**, see "Lovable" below                                                                                             |
| Open PRs             | four Dependabot major dev-dependency bumps, #9-#12 — see below                                                                                   |
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

## Product status by phase

- [x] **Phase 0 — research gate and Lovable/GitHub bootstrap**
- [x] **Phase 1 — monorepo and developer foundation**
- [x] **Phase 2 — contracts, domain, and database**
- [ ] **Phase 3 — ingestion, outbox, and Kafka** ← current
- [ ] Phase 4 — synthetic data and scoring
- [ ] Phase 5 — alerts and investigations
- [ ] Phase 6 — operations frontend
- [ ] Phase 7 — observability and resilience
- [ ] Phase 8 — security and quality hardening
- [ ] Phase 9 — performance and documentation
- [ ] Phase 10 — release

## Completed since last checkpoint — Phase 1

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
| 0006 | Event envelope, five business topics, account-keyed ordering, at-least-once with an outbox          |
| 0007 | Decimal money as JSON strings, UUIDv7 keys, `timestamptz`, forward-only Flyway migrations           |
| 0009 | Adopt Lovable's TanStack Start foundation; render client-side so Spring Boot stays the sole backend |

**Still needing an ADR:** 0005 outbox relay mechanics (ADR-0006 settles the semantics) · 0008
scoring-service boundary · 0010 model and evaluation choice · 0011 SSE versus WebSockets · 0012
authentication · 0013 observability · 0014 deployment strategy.

**Contracts:** `contracts/` exists and is validated in CI — OpenAPI 3.1 for `/api/v1`,
AsyncAPI 3.0 for the five topics, and seven JSON Schemas. `make contracts-check` compiles every
schema, validates every example, and asserts the deliberately-invalid ones are rejected.

## Known issues and technical debt

- **Node 22.19.0 on the reference machine** passed its LTS end date (2026-07-28). `engines`
  requires Node 24. Bun runs everything, so nothing is blocked, but local Node should be upgraded.
- **Default `JAVA_HOME` points at JDK 17.** JDK 25 is at `~/.jdks/jdk-25.0.4.1+1`;
  `make bootstrap` warns rather than fails, because `make up` builds the API in Docker. Hit
  directly on 2026-08-26: `./mvnw compile` fails with `release version 25 not supported` unless
  `JAVA_HOME` is set for the command. Spotless passes regardless, because formatting does not
  compile — a green formatter is not a green build.
- **Docker Desktop does not start quickly on the reference machine.** Start it before any session
  that touches persistence: every suite except the 23 unit tests needs it, and `make test-api`
  exists precisely so the fast half can run without it.
- ~~**`Alert.version` disagrees between the contract and the mapping.**~~ **Resolved 2026-08-26.**
  OpenAPI moved to `minimum: 0` with the reason written into the schema; `alert-updated.v1.json`
  keeps `minimum: 1` and now says why.
- **The published Temurin 25 image is one critical-patch build behind the local JDK.** Containers
  build on `25.0.4+7`, local `./mvnw` runs on `25.0.4.1+1`. Both are Java 25 LTS. Revisit when
  Adoptium publishes `25.0.4.1`.
- **`noUnusedLocals` / `noUnusedParameters` are still `false`** in `apps/web/tsconfig.json`.
- **Coverage thresholds are enforced in `apps/api` only** — LINE 0.50, BRANCH 0.40, set from a
  measurement on 2026-08-26 and raised only when a phase genuinely raises coverage. `apps/web` and
  `apps/scoring` still have none; set them in Phases 4 and 6.
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
- **`make` is not installed on the reference machine**, so Makefile targets are exercised there
  through `scripts/dev/sf.ps1` or by running the underlying command directly. Both are changed
  together, every time; a Makefile edit without the matching runner edit is a defect.
- **`ProcessedEvent`, `AuditLogEntry`, `RegisteredModel`, `AlertAction`, `Role`, `User` and
  `UserRole` have mappings and no callers.** They are validated against the schema and otherwise
  untouched, which is most of the remaining coverage gap. Phases 3 to 5 reach them.

## Open Dependabot pull requests

Four, all **major** dev-dependency bumps, deliberately kept out of the grouped minor/patch pull
request so each gets its own review and its own CI run:

| PR  | Bump                         | Note                                                   |
| --- | ---------------------------- | ------------------------------------------------------ |
| #9  | `@types/node` 22 → 26        | Low risk; the project targets Node 24+, so 22 is stale |
| #10 | `globals` 15 → 17            | Low risk, ESLint configuration only                    |
| #11 | `@vitejs/plugin-react` 5 → 6 | Needs a build and browser check                        |
| #12 | `eslint` 9 → 10              | Major; flat-config changes likely, review carefully    |

Each needs the same two steps: the lockfile workflow regenerates `bun.lock` on push, then
`gh pr close <n> && gh pr reopen <n>` re-triggers CI — a push made with the default `GITHUB_TOKEN`
cannot start workflow runs. See
[`docs/operations/BRANCH_PROTECTION.md`](docs/operations/BRANCH_PROTECTION.md).

Three earlier Dependabot pull requests were **closed with reasons** rather than merged, because
each would have broken a recorded decision: Temurin 25 → 26 (not an LTS, ADR-0003), Python
3.13 → 3.14 (joblib, ADR-0004), and nginx 1.30 stable → 1.31 mainline (verified by digest).
`.github/dependabot.yml` now carries ignore rules naming each decision, so none will be proposed
again.

## Blockers and required user input

None.

## Next three actions

Phase 2 is merged. Start from `main` and branch before editing.

1. **Write ADR-0005 — outbox relay mechanics.** ADR-0006 settled the semantics (at-least-once,
   account-keyed, an outbox); the relay's own behaviour is undecided and should be decided before it
   is written, not documented after. It needs to answer: polling versus logical decoding, how a
   batch is claimed so two instances cannot publish the same row twice, the backoff schedule and its
   ceiling, when an event moves from `PENDING` to `FAILED` rather than being retried again, and what
   an operator does with a `FAILED` row. The schema already carries `attempt_count`, `last_error`
   and `next_attempt_at`, so the ADR is choosing a policy over columns that exist.
2. **Build the validated ingestion endpoint.** `POST /api/v1/transactions` against the OpenAPI
   contract: Bean Validation on the DTO, RFC 9457 problem details, no JPA entity on the boundary,
   and the idempotency path returning the original result rather than a conflict. The constraint
   that makes it correct — `transactions_idempotency_unique` — is already tested at the schema
   level; this is what turns it into a product guarantee. Then the outbox write in the same
   transaction, which is the atomicity ADR-0006 depends on.
3. **Clear the four open Dependabot pull requests, #9 to #12.** Open since Phase 1. Each needs the
   same two steps: let the lockfile workflow regenerate `bun.lock` on push, then
   `gh pr close <n> && gh pr reopen <n>` to re-trigger CI, because a push made with the default
   `GITHUB_TOKEN` cannot start workflow runs. `#11` (`@vitejs/plugin-react` 5 → 6) needs a build and
   a browser check; `#12` (`eslint` 9 → 10) is a major with likely flat-config changes.

[`docs/architecture/TRANSACTION_TO_ALERT.md`](docs/architecture/TRANSACTION_TO_ALERT.md) is the
design for all of Phase 3 and is marked as design rather than as a running system. Remove that
marking as each step becomes real.

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
