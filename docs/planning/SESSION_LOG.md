# SentinelFlow Session Log

Append-only. One entry per meaningful session. Never rewritten except to correct a factual typo,
with an explicit note.

---

## 2026-08-25 — Phase 0: research, repository bootstrap, foundation audit

| Field           | Value                                                                                                                            |
| --------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| Start / end UTC | 2026-08-25T17:00Z / 2026-08-25T18:25Z                                                                                            |
| Starting SHA    | none (repository did not exist)                                                                                                  |
| Ending SHA      | `35b15f6` on `main`                                                                                                              |
| Objective       | Complete Phase 0: research gate, Lovable-first repository creation, foundation audit and corrections, tooling, CI, documentation |

### Work completed

**Research gate.** Ten entries in `docs/research/RESEARCH_LOG.md`, all primary sources. Read the
Spring Boot 4.1.1 BOM directly from Maven Central rather than trusting a search index, which had
stale versions. Locked Java 25 LTS, Spring Boot 4.1.1, Python 3.13, Node 24, PostgreSQL 18.6,
Kafka 4.2.1 KRaft.

**Toolchain.** Provisioned Temurin `jdk-25.0.4.1+1` from the Adoptium zip after `winget --scope
user` failed with exit 16 (MSI cannot install at user scope). SHA-256 verified before extraction.
Installed Bun 1.4.0 as the project's single package manager.

**Repository.** Lovable created `la3679/sentinelflow` after the user completed the one-time UI
authorization. Verified provenance from both sides before cloning: root commit `0f401e5` is
Lovable's template commit, followed by five `gpt-engineer-app[bot]` generation commits.

**Foundation audit.** Checked Lovable's claims against the code rather than accepting them.
18 of 23 checks passed as generated. Found a persisted `sessionStorage` demo session plus two
route gates — an authentication shape this phase excludes — and one dead dependency.

**Corrections.** Thirteen commits. Replaced the session concept with an explicit demo-operator
concept, configured client-side rendering so Spring Boot stays the sole backend, removed TanStack
Query, named the package, and added the entire test and CI layer that did not exist.

### Tests and results

`tsc --noEmit` exit 0 · `eslint .` exit 0 (7 vendored warnings) · `prettier --check` pass ·
Vitest 24/24 · coverage 40.4% lines · Playwright 58/58 · axe 0 WCAG 2.1 A/AA violations across
8 routes and 2 viewports · `bun run build` exit 0 · gitleaks 0 leaks over 20 commits.

### Decisions

ADR-0001 (Lovable-first repository creation), ADR-0003 (Java 25 + Spring Boot 4.1.1),
ADR-0004 (Python 3.13 via uv), ADR-0009 (adopt TanStack Start, render client-side).

ADR-0009 and research entry R-09 state plainly that Lovable currently generates new applications
with TanStack Start — its present default generation stack, not a mis-selected template.

### Things that went wrong, and what they taught

- **`eslint .` reported 8927 errors on first run.** All CRLF artefacts: committed blobs are
  LF-only, the machine's global `core.autocrlf` is `true`, and the repository had no
  `.gitattributes`. Lovable's "lint clean" claim was true on Linux and false on Windows. Fixed by
  committing `.gitattributes`.
- **Two CI jobs failed on their first real run.** gitleaks needed `pull-requests: read` to
  enumerate PR commits; dependency review is unavailable on private repositories without paid
  Advanced Security, so it is now gated on visibility rather than disabled.
- **The e2e CI job failed because it never built.** It passed locally only because a stale `dist/`
  existed. Reproduced locally by deleting `dist/`, then fixed with an explicit build step. A good
  argument for never trusting a green local run that a clean checkout has not reproduced.

### Blockers

One, now resolved: the Lovable→GitHub source-control connection has no MCP surface and required a
one-time UI authorization by the user.

### Next actions

Phase 1 — monorepo restructure, developer tooling, Compose stack, ADR-0002, and `main` branch
protection.

---

## Session 2 — 2026-08-25 — Phase 1: monorepo and developer foundation

**Outcome:** Phase 1 complete. Twelve commits on `chore/phase-1-foundation`, PR
[#2](https://github.com/la3679/sentinelflow/pull/2), all six workflows green.

### What was built

The repository went from "a React app with some docs" to a polyglot monorepo with three
buildable, containerised, CI-gated applications and a local stack that comes up healthy in one
command.

`apps/api` (Spring Boot 4.1.1 on Java 25), `apps/scoring` (FastAPI on Python 3.13 via uv), and
`apps/web` (the relocated console) each own their build, tests, gates and Dockerfile. `compose.yaml`
orchestrates all three plus PostgreSQL 18.6, Kafka 4.2.1 in KRaft, Prometheus and Grafana. A
`Makefile` and a native PowerShell runner give the same command surface on either platform.

### Decisions worth recording

**Moving the console to `apps/web/` ends Lovable's generation capability.** Lovable has no
documented support for an application outside the repository root. This was the significant
judgement call of the phase: the monorepo layout is what makes the project's central claim true,
and Lovable's job — creating the repository and delivering an audited frontend foundation — was
finished at the end of Phase 0. Recorded in ADR-0002 and `LOVABLE_GITHUB_WORKFLOW.md` rather than
left for someone to discover, along with the two honest routes back to a design session.

**No CI path filtering.** A workflow skipped by an `on: paths` filter produces no check run at
all, and a required check that never reports blocks a pull request permanently. The suite is a few
minutes; filtering is worth its complexity later, not now.

**The post-compaction reminder is a `SessionStart` hook, not a `PostCompact` hook.** `PostCompact`
exists but has no decision control in the current schema — it can log, it cannot add context.
Following the actual schema beat following a literal reading of the standards.

### Defects found by running things rather than reading them

Ten, all fixed in the commit that found them. The pattern is the point: **every one of these would
have passed a code review.**

1. **Maven Wrapper checksum failure inside the container.** Presented as a supply-chain alarm. The
   Temurin image ships no curl, wget or unzip, so the wrapper fell back to a bundled Java
   downloader that does not follow Maven Central's redirect and saved the redirect body. The
   checksum earned its place on its first real use.
2. **`java -jar` cannot start an extracted Spring Boot layout.** `extract --launcher` produces an
   exploded app; it needs `JarLauncher`.
3. **uv installs the project editable by default**, leaving a `.pth` pointing at the build stage's
   source path. The runtime image has no such path, so the service died at import.
4. **PostgreSQL 18 refuses to start on the old volume path.** From 18 the image stores its cluster
   in a major-version subdirectory; the mount is `/var/lib/postgresql`, not `.../data`.
5. **`mvnw` was committed without its executable bit.** Windows does not track it and `chmod`
   there changes nothing Git can see. It arrived on Linux as 0644 and broke the API workflow and
   the API container build with exit 126 — one defect, two red jobs.
6. **Starlette 1.6 requires `httpx2`.** pytest's `filterwarnings = ["error"]` turned a deprecation
   into a build failure instead of scrollback, on the first run.
7. **Git Bash rewrites `/opt/kafka/bin/...` into a path inside the Git installation** before
   handing it to a native binary. `MSYS_NO_PATHCONV` fixes it — but it cannot be exported, because
   curl needs the ordinary conversion for its temp file. That took two attempts.
8. **Windows PowerShell 5.1 turns native stderr into a terminating ErrorRecord.** Under
   `$ErrorActionPreference = 'Stop'`, the script died the first time `docker info` printed a
   harmless warning.
9. **`Invoke-WebRequest` returns `.Content` as a byte array** for any media type it does not
   recognise as text — including Actuator's `application/vnd.spring-boot.actuator.v3+json`. Two
   health assertions passed the status check and failed the body check.
10. **`make help` silently omitted `test-e2e`**, because the regex was `[a-zA-Z_-]` and the target
    name contains a digit.

Two more surfaced from the platform rather than the code: the repository's dependency graph and
security features were off, which made `dependency-review-action` fail with a misleading "not
supported on this repository"; and the container scan found two fixable HIGH advisories in the
scoring image, both inside the base image's bundled pip. The right fix there was not a version
bump but deleting an installer that a runtime image has no business carrying.

### Verification

The full evidence table is in `PROJECT_STATE.md`. The gate that mattered most was the clean-clone
test: a fresh clone of `55efe6a`, bootstrapped, frozen-installed, and all three applications built
and tested — not the working copy that had been coaxed into working.

That test also produced a real Windows finding worth keeping: cloning into a deep path makes
nested `node_modules` exceed the 260-character limit and `bun install` fails with `ENAMETOOLONG`.
It is now in the README's troubleshooting table.

### Blockers

None.

### Next actions

Merge PR #2, then Phase 2: ADR-0006 and ADR-0007 before any schema, then `contracts/`, the first
Flyway migrations, and their Testcontainers PostgreSQL tests.

### Post-merge — Dependabot's first run

Phase 1 merged as [#2](https://github.com/la3679/sentinelflow/pull/2). Dependabot then acted on
its new configuration within a minute and opened four pull requests, three of which would have
broken a recorded decision:

- **Temurin 25 → 26.** Java 26 is not an LTS release; ADR-0003 pins 25 LTS on purpose.
- **Python 3.13 → 3.14.** ADR-0004 and R-2026-08-25-06 pin exactly 3.13 because joblib declares
  support only through it. It would also have put the image ahead of `pyproject.toml` and broken
  `uv sync --frozen`.
- **nginx 1.30.4 → 1.31.3.** nginx uses even minors for stable and odd for mainline, so this is a
  move to mainline. Verified by digest rather than assumed: `1.30.4-alpine3.24` and
  `stable-alpine3.24` resolve to the same image.

All three were closed with the reason stated on the pull request, and `dependabot.yml` now carries
ignore rules that name the decision each one protects. Patch updates still flow, which is where
security fixes actually arrive.

The fourth revealed something structural. Dependabot edits `apps/web/package.json` and never
regenerates `bun.lock` — and specifically does not for a Bun workspace
([dependabot-core#11602](https://github.com/dependabot/dependabot-core/issues/11602),
[#14223](https://github.com/dependabot/dependabot-core/issues/14223)). Every npm pull request it
opens therefore fails at `bun install --frozen-lockfile`. A probe confirmed Bun is _not_ a separate
`package-ecosystem`: GitHub folded it into `npm_and_yarn`, so the declaration was already right and
only the lockfile handling is missing.

Dropping `--frozen-lockfile` would have made the red go away and quietly destroyed the
reproducibility the phase gate had just tested. Instead a workflow regenerates the lockfile and
pushes it onto the Dependabot branch — the only workflow in this repository with `contents: write`,
gated on the Dependabot actor.

It then produced its own lesson. The workflow's comment claimed the lockfile commit re-runs CI. It
does not: a push made with the default `GITHUB_TOKEN` cannot start workflow runs, so the pull
request ended up correct and with **zero checks**, which a required-checks ruleset reads as blocked.
`gh pr close && gh pr reopen` fixes it, verified by running it — all ten checks then passed,
including a `vite` 8.1.5 → 8.2.2 bump. The comment was corrected, because an inaccurate comment in
the one workflow holding write access is worse than none.

Four major dev-dependency bumps (#9–#12) remain open by design, each awaiting its own review.

### Phase 2 begins — decisions before schema

Two ADRs and the contract baseline, in that order, and all three before a single migration exists.

**ADR-0007** settles money, identifiers, time, and how the schema may change. The decision most
worth its argument is money as a **JSON string**: `JSON.parse` produces a `double`, so a JSON
number is silently rounded by every JavaScript consumer before application code sees it. There is
now an `examples/invalid/money-as-number.json` fixture that fails the build if that rule is ever
relaxed.

**ADR-0006** settles the event contract. The one that constrains the most is the partition key:
transaction and risk events are keyed by **account**, not transaction, because Kafka orders only
within a partition and velocity rules need one account's events in order. Keying by transaction
would have spread an account across every partition and destroyed exactly the ordering the rules
depend on. The consequence — a hot account is a hot partition — is written down rather than left to
be discovered.

Exactly-once was rejected as a goal rather than quietly claimed. It is not achievable across a
database, a broker and an HTTP call without distributed transactions; at-least-once with idempotent
consumers reaches the same observable outcome and is honest about how.

**`contracts/`** then makes all of it enforceable. Seven JSON Schemas, an AsyncAPI document and an
OpenAPI baseline, plus a checker wired into CI.

The part that makes it a contract rather than documentation is the negative cases: the checker
asserts that deliberately-invalid examples are **rejected**. That was verified by breaking a schema
on purpose — deleting `const: "NEW"` from `alert-created.v1.json` makes the check exit 1 and name
the fixture that should have failed.

Two defects in the checker itself surfaced from running it. AJV registers a schema under its own
`$id` as well as the supplied key, so a second registration collided on all seven schemas. And the
AsyncAPI parser given only a file's contents has no base path, so every `../schemas/*.json`
reference failed to resolve; it needs `fromFile`. Both are the same lesson as Phase 1's ten: reading
the code would not have found either.

### Phase 2 continues — the schema, and a session that could not run it

The six Flyway migrations covering all fifteen tables in §9 are written, along with the domain
value types and the first six JPA entities. **None of it has been executed.** Docker Desktop was
not running when this session started; it was launched and had still not brought its engine up by
the time the checkpoint threshold arrived. Nothing here is claimed to work.

What _was_ run, on 2026-08-26: `./mvnw spotless:apply` and `./mvnw compile` under
`~/.jdks/jdk-25.0.4.1+1` (both exit 0), `bun run format:check` (pass), `check-docs.mjs` (77 links
across 30 files, 0 broken), and `check-contracts.mjs` (pass). Compilation is evidence the Java is
well-formed. It is not evidence that a single migration applies or that a single mapping matches
its table, and `ddl-auto: validate` means the service will refuse to start if any of them do not.

The default `JAVA_HOME` on the reference machine still points at JDK 17, which is already recorded
as known debt. It surfaced here as `release version 25 not supported` — worth noting that Spotless
passed anyway, because formatting does not compile. A green formatter is not a green build.

Three schema constraints took the most thought and are the ones the constraint tests will target
first. `transactions (account_id, idempotency_key)` **is** the idempotency guarantee — an
application-level check-then-insert has a window between its two statements and this does not.
`risk_assessments_degraded_consistent` makes a half-degraded assessment unrepresentable, which is
what a partially-failed scoring call would otherwise persist and a consumer would later read as a
real model output. `model_registry_single_active_idx` allows exactly one `ACTIVE` model, because a
second one makes "which model scored this" ambiguous for every assessment written while it lasted.

One contract tension surfaced and is **not yet resolved**: OpenAPI gives `Alert.version` and
`AlertTransitionRequest.expectedVersion` a `minimum` of 1, while Hibernate's `@Version` seeds a new
entity at 0. The schema currently permits `version >= 0`. Either the contract moves to 0 with a
stated reason or the mapping compensates; picking one is a Phase 2 action, not something to leave
for whoever writes the alert service in Phase 5 to discover.

A defect in `scripts/claude/checkpoint.mjs` was found by reading its own output: `run` trims, which
removes the leading space of the **first** porcelain line only, so a fixed-offset slice dropped the
first character of that path and left every other one intact. It printed `pps/api/pom.xml`. Fixed
and verified. A helper that exists to stop facts being typed from memory and typed wrong is worse
than nothing when it corrupts one authoritatively.

---

## Session 4 — 2026-08-26 — Phase 2: running the schema, and everything that found

| Field           | Value                                                                          |
| --------------- | ------------------------------------------------------------------------------ |
| Start / end UTC | 2026-08-26T13:20Z / 2026-08-26T14:05Z                                          |
| Starting SHA    | `67ac508` on `feat/domain-and-migrations`                                      |
| Ending SHA      | `4eca035` on `feat/domain-and-migrations`                                      |
| Objective       | Execute the unrun Phase 2 schema, finish the mappings, and close the phase out |

**Outcome:** six commits. The schema that had been written and never run now runs, all fifteen
tables are mapped and validated, and the constraint suite, the seed foundation and the two diagrams
Phase 2 owes are in place.

### The session's actual subject: nothing had been executed

The previous session committed six migrations, three domain types and four entity mappings without
a database. Everything below came out of finally running it, and none of it was visible by reading.

**Three defects in the first `./mvnw verify`, in the order they surfaced.**

`PostgreSQLContainer<?>` does not compile. Testcontainers 2.x moved the class to
`org.testcontainers.postgresql` and dropped the self-referential type parameter, so every example
written against 1.x is wrong here.

`spring.datasource.hikari.connection-timeout: 10s` failed startup with `NumberFormatException`.
Everything under that prefix binds straight onto `HikariDataSource`, whose setters take a `long`;
Boot's `Duration` conversion never gets a chance. That was committed, unrun, and would have failed
identically in production.

`spring-boot-flyway` was absent. In Spring Boot 4 the autoconfiguration is its own module.
`flyway-core` was on the classpath, every `spring.flyway.*` property bound to nothing, no migration
ran — and the only symptom was Hibernate reporting `missing table [customers]`, which reads exactly
like a mapping defect and is not one. That is the one worth remembering: the error named the wrong
layer.

### A state-file discrepancy, corrected in favour of Git

`PROJECT_STATE.md` and commit `78290dc` both said six of fifteen tables were mapped. Four were.
`Merchant` and `Account` were described in that commit's own message and never written. Git is
right; the state file is corrected and this is the note the workflow rules require.

### Alert.version, resolved rather than carried forward

OpenAPI required `minimum: 1`; Hibernate seeds a new `@Version` at 0; the `CHECK` permits 0. The
contract moved to `minimum: 0` with the reason written into the schema. The version is an opaque
concurrency token — a client echoes it back as `expectedVersion` and compares it for equality —
so bending the mapping would have bought a translation layer whose only job was hiding the ORM's
counter from someone who cannot interpret it anyway. `alert-updated.v1.json` keeps `minimum: 1`
and now says why: that event only ever describes a change, so the version has already been
incremented by the time one is published.

### What the tests are actually asserting

Every constraint test names the constraint it expects to fire. "Something failed" would let a test
pass on a fixture typo, a missing not-null, or a foreign key nobody meant to trip, which is how a
constraint quietly stops being tested while its test stays green. Where a rule has a permitted
counterpart the counterpart is asserted too — the same idempotency key on a _different_ account
must succeed — because a schema that rejects everything also passes a suite that only checks for
rejection.

Two things the run corrected in the tests themselves. The optimistic-lock test first re-read the
alert _after_ the winning write, which is not stale; it now detaches before the winner commits. And
it asserts `jakarta.persistence.OptimisticLockException`, not Spring's translated type, because
translation happens in a `@Repository` proxy and that test drives the `EntityManager` directly.

`make test-api` and `make test-integration` are now different things. The service cannot start
without PostgreSQL, so leaving every test in one target would have made `make test` require Docker,
which is not what "all standard suites" has meant here.

### Seed determinism is a specification claim, not an observation

The loader uses only `java.util.Random` and only its single-argument `nextInt(int)`. The
two-argument form is a `RandomGenerator` default method whose implementation the specification does
not pin, and the entire claim is that seed `20260826` reproduces on someone else's machine.

The manifest checksums generated _references_, not identifiers: identifiers are UUIDv7 and embed
the millisecond they were minted, so two identical runs necessarily differ there and hashing them
would prove nothing. Row contents are asserted directly instead.

The data is synthetic by construction rather than by anonymisation — there is no column for a name,
an address or a card number anywhere — and a test asserts no such column has appeared, so adding
one fails rather than quietly giving the seed somewhere to put it.

### Documentation that cannot go stale silently

`DATA_MODEL.md` deliberately does not repeat the column lists; the migrations are authoritative and
a duplicated list misleads someone eventually. `SchemaDocumentationIT` asserts the ER diagram's
entity blocks are exactly the tables that exist, because to a compiler a diagram is prose and
nothing else in the build would notice it drifting.

### Tests and results — every figure from a run on 2026-08-26

| Command                                      | Result                                                 |
| -------------------------------------------- | ------------------------------------------------------ |
| `./mvnw verify` (JDK 25.0.4.1+1)             | **PASS** — 23 unit, 57 integration, coverage check met |
| `./mvnw verify -DskipITs -Djacoco.skip=true` | **PASS** — 23/23                                       |
| JaCoCo, both suites                          | 62.0% lines (432/697), 63.8% branches (60/94)          |
| `bun scripts/dev/check-contracts.mjs`        | **PASS**                                               |
| `bun scripts/dev/check-docs.mjs`             | **PASS** — 89 links across 33 files, 0 broken          |
| `bun run format:check`                       | **PASS**                                               |
| `bun run typecheck` (web)                    | **PASS**                                               |
| `bun run test` (web)                         | **PASS** — 24/24                                       |

The coverage floor was set to 0.50 line / 0.40 branch after the first measurement of 52.4% / 46.9%,
deliberately below it: a threshold at the measurement fails the next honest refactor, and one chosen
before a measurement is a guess. The later 62.0% / 63.8% is the same gate with the seed and
documentation suites added.

`make` is still absent on the reference machine, so the Makefile changes were verified by running
the equivalent Maven invocations directly and the PowerShell runner was updated in the same commit.

### Addendum — Phase 2 merged, and the CVE that blocked it

PR [#21](https://github.com/la3679/sentinelflow/pull/21) merged at `c38934f`. All ten required
checks passed, and all six workflows passed again on `main` afterwards. The GitHub runner ran the
Testcontainers suites for real — its log shows six migrations applied and 23 unit plus 57
integration tests — so the phase's evidence no longer rests on one machine.

It did not merge on the first attempt. `Build and scan scoring` and `Build and scan web` failed on
**CVE-2026-14456**, an OpenSSL denial of service via unbounded memory growth in the QUIC server
path. Both are required checks, so it blocked every pull request in the repository — and it had
nothing to do with Phase 2. `main` was green the day before; the advisory is newer than its last
container run.

The obvious fix was unavailable: neither base image had been rebuilt with the patch.
`python:3.13-slim-trixie` still shipped `3.5.6-1~deb13u2` and
`nginx-unprivileged:1.30.4-alpine3.24` still shipped `3.5.7-r0`, both checked by running them rather
than assumed. But both distributions had published the fix, so the update was taken from the archive
at build time — pinned to the exact fixed version, so an image stays a function of its Dockerfile
rather than of the day it was built, and with the removal condition written beside it.

A `.trivyignore` would have been faster and wrong. The rule here is that a security finding is never
suppressed to go green, and the distinguishing fact was that this one is genuinely patched and
genuinely available: suppressing it would have traded a real fix for a quiet one. Both images were
rebuilt and scanned locally with Trivy 0.70.0 under CI's own flags — 0 findings, exit 0 — and the
web image was checked with `id` to confirm it still runs as uid 101 after the `USER root` layer.

That went out as PR [#22](https://github.com/la3679/sentinelflow/pull/22), merged first, then
`main` was merged into the Phase 2 branch. Keeping it separate mattered: a security fix and a schema
phase are not one change, and a reviewer looking for why an OpenSSL version is pinned should not
have to find it inside a database commit.

---

## Session 5 — 2026-08-26 — Phase 3 begun, then blocked by a GitHub Actions outage

| Field           | Value                                                                     |
| --------------- | ------------------------------------------------------------------------- |
| Start / end UTC | 2026-08-26T14:35Z / 2026-08-26T16:00Z                                     |
| Starting SHA    | `7dd5eee` on `main`                                                       |
| Ending SHA      | `55f37f7` on `feat/outbox-relay`, stacked on `feat/transaction-ingestion` |
| Objective       | Phase 3: the relay decision, ingestion, and the outbox drain              |

**Outcome:** ADR-0005 written and merged. Ingestion and the relay are written, tested locally, and
pushed — and unmerged, because GitHub Actions went down partway through.

### What was built

**ADR-0005** decides the relay before the relay exists: polling rather than logical decoding,
`FOR UPDATE SKIP LOCKED` for the batch claim, exponential backoff with full jitter bounded at ten
attempts, `FAILED` as terminal and non-automatic to revive, and five metrics from the first commit
including both depth and age — depth alone cannot tell a busy relay from a stuck one.

**Ingestion** turns the schema's idempotency constraint into a product guarantee. The service's
lookup is an optimisation, not the guarantee: eight concurrent submissions of one key all pass it,
and `transactions_idempotency_unique` is what makes exactly one transaction and one outbox event
exist. Three outcomes kept distinct — 202 created, 200 replayed, 409 for a key reused with a
different payload, because answering 200 there would leave a caller believing a transaction it never
submitted was recorded.

**The relay** drains that outbox to Kafka, claim and publish and status update in one transaction.

### Defects found by running, not reading

- **Jackson coerced a JSON number into the money `String` field.** `"value": 1249.99` was accepted,
  the money pattern then matched, and ADR-0007's central rule had been broken by the parser before
  any of this project's code ran — with whatever the sender's own `double` had rounded to.
- **`default-property-inclusion: non_null` dropped `deviceReference`** from the event payload, which
  the schema requires present and null.
- **`spring-boot-kafka` was missing**, exactly as `spring-boot-flyway` had been: in Spring Boot 4 the
  autoconfiguration is its own module, so the library alone means no `KafkaTemplate` and every
  `spring.kafka.*` property binding to nothing. Second time this shape has bitten; worth expecting a
  third.
- **`KafkaTemplate.send` does not always return a failed future** — it throws synchronously when the
  producer cannot fetch metadata, which escaped the publisher's contract.
- **A unit test read `../../contracts`.** `apps/api/Dockerfile` builds from a module-only context, so
  it failed inside the image build where CI runs the unit suite, and could not fail locally. Contract
  tests that read repository files are ITs now, by rule.

### The outage

`Build and scan api` and `Build and scan web` failed on **CVE-2026-14456**, a new OpenSSL advisory,
before any of this. That was fixed properly rather than suppressed — see the Phase 2 addendum — and
merged as its own pull request.

Then Actions itself went down. GitHub declared `major_outage` at **15:11Z**; runs from 15:05 onward
queue and never start, two returned `startup_failure` in seconds on unchanged workflow files, and
`gh run cancel` refuses stuck runs as "completed" while the API still reports them `queued`.

Three explanations were checked before concluding it was upstream. Every job already uses the generic
`ubuntu-latest` pool, so there is no narrow label to widen. The concurrency groups are per
`github.ref` with `cancel-in-progress: true`, so a ghost run holding a group would have _cancelled_
one of a queued pair rather than leaving both — and a group lock does not produce a `startup_failure`
either. The status API confirmed the rest.

Closing PR #25 and opening #26 on the same branch was worth doing and did dispatch more workflows
than a plain push had, but nothing executes while the incident is open.

### What that leaves

Two branches pushed and unmerged, stacked. `./mvnw verify` on the tip gives 23 unit and 89
integration tests passing with the coverage gate met, the api image builds, and the contract, docs
and formatting checks pass — on one machine, which is not CI and is not claimed to be. Landing order
and the recovery procedure are in `PROJECT_STATE.md`.

---

## Session 6 — 2026-08-26 — the outage clears, the stack lands, and Phase 3's consumer half

Session 5 ended with two branches pushed and unmerged behind a GitHub Actions `major_outage`. This
session began by checking whether that was still true rather than assuming either way:
`githubstatus.com/api/v2/summary.json` reported every component `operational` with no open incidents.

### The ghost runs had to be re-dispatched by hand

Actions being healthy did not revive the seven runs the outage had stranded. Each still reported
`queued` through the API while `gh run cancel` refused it as "completed" — the same contradiction
Session 5 recorded, now permanent for those runs. They will never finish and never cancel.

Closing and reopening PR [#26](https://github.com/la3679/sentinelflow/pull/26) dispatched a fresh
set, and all six workflows ran in minutes. Ten required checks green, and the api job ran the
Testcontainers suites on the runner rather than merely compiling them. Merged.
`feat/outbox-relay` was then brought up to date with `main` by merge rather than rebase — nothing on
it needed rewriting, and the two relay commits are worth keeping as they were written — opened as PR
[#27](https://github.com/la3679/sentinelflow/pull/27), and merged on ten green checks.

### The Dependabot workflow has a gap its own comment does not cover

`dependabot-bun-lockfile.yml` regenerates `bun.lock` on a Dependabot pull request, and its header
documents the known limitation that a push made with the default `GITHUB_TOKEN` cannot start further
workflow runs — with `gh pr close <n> && gh pr reopen <n>` as the remedy.

That remedy works for CI. It does not work for the lockfile job itself, because the job is gated on
`github.actor == 'dependabot[bot]'` and a human reopening the pull request **is** the actor. So on
[#11](https://github.com/la3679/sentinelflow/pull/11), where the lockfile had never been regenerated
at all, reopening re-dispatched every workflow and skipped the one that would have fixed them. The
job reported `skipping`, which reads like a success.

Regenerated by hand instead — which is what the workflow's own comment says a human changing a
manifest is expected to do — and verified locally before pushing rather than left to CI.

### A lint-count change that was not what it looked like

`bun run lint` reported 23 warnings against the 7 that `PROJECT_STATE.md` records, and the obvious
suspect was ESLint 10 on [#12](https://github.com/la3679/sentinelflow/pull/12). It was not. Comparing
lockfiles across three commits showed `eslint-plugin-react-refresh` had gone 0.4.26 to 0.5.4 in an
earlier grouped bump that is **already on `main`**, and 0.5.x flags a TanStack Start route file's
`Route` export that `allowConstantExport` used to cover. ESLint 10 itself produces zero errors.

Worth the ten minutes: the alternative was attributing a pre-existing change to the pull request in
front of it and either blocking that pull request or "fixing" the warnings inside it.

### Phase 3's consumer half

The producer was merged; this is the other side of the at-least-once bargain.

**The ledger row and the effect are one transaction, and the row goes first.** The claim is an
`INSERT ... ON CONFLICT DO NOTHING` rather than a read-then-write, because at-least-once delivery is
exactly the traffic that finds the window between the two. It is also not exception-driven: a
constraint violation marks a PostgreSQL transaction rollback-only, so the ordinary case — "seen this
before, do nothing, commit the offset" — could not commit. A row count keeps a duplicate an answer
rather than an error to recover from.

**Failures are classified, and the classification is carried rather than inferred.** An unreadable
envelope, an unknown `eventType`, an unsupported `schemaVersion` and a payload of the wrong shape go
straight to the dead-letter topic after one attempt; a handler's exception is retried on a bounded,
fully-jittered schedule and dead-lettered as `RETRY_EXHAUSTED` when the budget runs out.

**Retries block the partition on purpose.** The non-blocking alternative moves the record to a retry
topic, which loses the per-account ordering ADR-0006 §2 keys these events by and that velocity rules
depend on. The budget is an order of magnitude shorter than the relay's for the matching reason: the
relay waits for a broker holding one row, a consumer holds up everything queued behind it.

`FullJitterBackOff` is ten lines of our own rather than Spring's `ExponentialBackOff` with its
`jitter` property. That property perturbs the interval by plus-or-minus jitter, which spreads a
thundering herd across a few percent of the window; ADR-0006 §4 asks for a uniform draw across all of
it. Two different distributions with one name, and a test asserts which one this is.

### The DLQ contract cannot hold an unparseable message, and that was left as a decision

`dlq-record.v1.json` requires `originalEvent` to be a complete valid envelope. A message that is not
an envelope at all therefore has no representation — and ADR-0006 §4 independently forbids copying an
unsanitised payload fragment onto a topic operations staff read, so there is nothing legitimate to
put in the record either.

Relaxing the schema was the obvious move and was rejected. Adding a raw-message field would carve an
exception into an accepted ADR quietly, which the workflow rules call re-deciding.

What ships instead: such a message is logged at `ERROR` with its exact topic, partition and offset,
counted under `sentinelflow_consumer_undeliverable_total`, and its offset committed. The original
bytes stay readable at those coordinates for as long as retention holds them, which is strictly more
than a copy elsewhere would give, and the partition does not stop. An integration test publishes
malformed JSON ahead of a valid event and asserts the valid one is still handled.

### Defects found by running, not reading

- **The `@KafkaListener` broke twenty-three existing tests.** A listener container whose bootstrap
  address does not resolve fails the whole application context during startup rather than retrying,
  so every Postgres-only suite failed with `No resolvable bootstrap urls`. Nine new tests passed and
  twenty-three unrelated ones went red. Fixed with `sentinelflow.consumer.enabled`, mirroring the
  relay's flag, on by default and off in `AbstractPostgresTest`.
- **A `@TestConfiguration` that implements the interface it provides is injected twice.** The
  configuration class is itself a bean, so the consumer's `List<TransactionCreatedHandler>` would have
  held both it and its `@Bean` result, and every delivery would have been counted twice. Caught while
  writing it; the configuration and the handler are separate classes now.

### Tests and results — every figure from a run on 2026-08-26

| Command                                  | Result                                                        |
| ---------------------------------------- | ------------------------------------------------------------- |
| `./mvnw verify` (JDK 25.0.4.1+1)         | **PASS** — 41 unit, 107 integration, coverage gate met        |
| JaCoCo over both suites                  | 77.6% lines (944/1216), 66.1% branches (144/218)              |
| `bun scripts/dev/check-contracts.mjs`    | **PASS**                                                      |
| `bun scripts/dev/check-docs.mjs`         | **PASS** — 98 links across 34 files, 0 broken, 0 placeholders |
| `bun run format:check` (repository-wide) | **PASS**                                                      |
| `bun install --frozen-lockfile`          | **PASS** on every Dependabot branch after regeneration        |

The coverage gate was ratcheted from 50/40 to 70/60 — measured first, then set below the
measurement, as the previous turn of the ratchet was.

### Decisions worth recording

- **Merge, not rebase, to bring stacked branches up to date.** Nothing on them needed rewriting, and
  the workflow rules forbid rewriting published history.
- **No `TransactionCreatedHandler` implementation ships.** Scoring is Phase 4 and is the first thing
  that will genuinely act on one of these. The consumer injects a `List` and dispatches to every
  implementation, so Phase 4 adds a bean rather than editing the consumer — and the tests register
  their own handler through that same seam, with no test-only branch in production code. A no-op
  implementation would have been dead code pretending to be a feature.
- **Dead-lettering a transaction event marks the transaction `FAILED`.** That means the pipeline will
  not produce an assessment, not that the transaction was rejected. Left `PENDING` it would wait for
  something that is not coming, and "transactions awaiting assessment" would only ever grow.
- **`docs/operations/RUNBOOKS.md` documents three failures and says why it documents only three.** A
  runbook for a component that has not been built is a guess, and a guess in a runbook is worse than
  a gap because somebody follows it.

### Blockers

None.

### Next actions

1. Watch PR [#28](https://github.com/la3679/sentinelflow/pull/28) to green and merge it, and #12 with
   it.
2. ADR-0008, the scoring-service boundary, before Phase 4's handler is written.
3. Phase 4 — synthetic scenario generation and the scoring service, which brings the first real
   `TransactionCreatedHandler`.

---

## Session 7 — 2026-08-26 — Phase 4 opened: the boundary, the data, and the contract

Three pieces of Phase 4 landed, in the order that leaves the fewest decisions to be made by
accident: the boundary decision, then the data the model will be trained on, then the contract both
implementations will be written against.

### ADR-0008 first, because it was about to be decided by whichever line ran first

Phase 3 shipped a consumer with a deliberately empty handler seam. That left the API-to-scoring
boundary open, and an open boundary closes itself the moment somebody writes a call.

Synchronous HTTP from inside the handler, not an event round trip — rejected on three grounds rather
than on taste: the transaction-to-assessment correlation would become eventual and every screen
would have to render the window; idempotency would need solving a second time with its own ledger;
and the Python service would acquire a broker client, a consumer group and an outbox for one call.
The cost is stated rather than glossed — the assessment path is now coupled to scoring being
reachable — and it is smaller than it looks, because ingestion commits and publishes before any of
this runs.

**Two gaps were found by reading the ADR back against ADR-0002 rather than by anything failing.**

The first was a hole. The ADR settled that the call is HTTP and never said what is _in_ it. Several
features this project needs are historical, and the scoring service has no database — so left
unsaid, the first implementation either gives it one, making two services systems of record for a
table `apps/api` owns, or discovers the problem after the contract is written. The request now
carries a bounded, versioned account context the API computes.

The second was a collision. ADR-0002 assigns "evaluation metrics and thresholds" to `apps/scoring`
and ADR-0008 gives the alerting threshold to the API, which reads as a contradiction. It is not:
they are two objects. A model has an operating point that belongs in its model card next to the
precision and recall measured at it; the alerting policy is applied at runtime to a final score that
also folds in a rule score the model never saw, and it has to apply identically when the model did
not answer at all.

### The generator plants shapes, not suspicious-looking rows

Six of them — a velocity burst, an amount spike relative to that account's own baseline, a
card-testing run, an improbable journey, a proportional account drain, an off-hours purchase from an
unknown device. Every one needs history to see, which is the whole reason to generate data: a rule
that only has to notice one large amount can be written without any of this and says nothing about
whether the pipeline works.

The background is not filler either. An account's spending is drawn around a baseline derived from
its own reference, it favours a few merchants it has used before, and it uses one of two devices —
which is what makes "a merchant this account has never used" mean anything when a shape breaks the
habit. Traffic where every account behaved identically would make a velocity feature trivially
predictive and any evaluation of it meaningless.

**Labels never enter the database**, and `ScenarioLoaderIT` asserts it against `information_schema`
rather than trusting the intent. It writes through `TransactionWriter`, so generated traffic gets the
same validation and the same outbox row as a posted transaction and flows through the relay and the
consumer exactly as real traffic does. A private insert path would be faster and would prove nothing.

### Two defects found by running it

**Hibernate logs every aborted statement at WARN with the full SQL and all bound values.** A
duplicate idempotency key is normal traffic under at-least-once ingestion — handled, with the caller
getting its original result back — and it printed the amount, both references, the device handle and
the key. Wrong twice over: it turns the expected path into an alarm, and those values are exactly
what this project's own rules say not to log. The data is synthetic, but "the logs happen to be safe
because the data is fake" is not a control.

**Content-derived idempotency keys collide.** Two ordinary purchases on one account, at one merchant,
in the same second, for the same amount are entirely possible in fourteen days of traffic, and the
second would have been silently rejected as a duplicate — leaving a dataset smaller than the manifest
claimed. Caught by a test asserting uniqueness over the `DEMO` profile, not by the loader failing.

### The contract, and a checker that only checked one document

`contracts/openapi/sentinelflow-scoring.yaml` is written before either implementation, the same order
the public API document was written in. `check-contracts.mjs` was hardcoded to
`sentinelflow-api.yaml`, so the new document would have been a second authoritative contract that
nothing validated — precisely what "contracts/ is authoritative" exists to rule out. It now reads the
directory, and that was verified by pointing it at a deliberately broken document rather than assumed.

Two schema fields exist because their absence is a silent wrong answer rather than a missing one.
`lookbackWindowSeconds` states how far back the context reaches, so a feature defined over 24 hours
that received an hour can say so in `warnings`. `truncated` says the list hit its cap, which turns a
count into a floor.

### The README was the most out-of-date document in the repository

It said "Phase 1 of 10 complete" with Phase 2 listed as next, and drew the risk consumer as planned.
A README that overstates progress is the usual failure, which is what makes one that understates it
no better as a claim about accuracy — either way the document and the repository disagree.

The testing section is now split by date rather than refreshed wholesale. The API and console figures
were measured on 2026-08-26 and say so; the browser, accessibility, scoring and smoke figures were
not re-measured and keep their 2026-08-25 date. A figure that inherits a newer date it was never
measured on is the exact thing this project's evidence rule exists to prevent.

### Tests and results — every figure from a run on 2026-08-26

| Command                                  | Result                                                         |
| ---------------------------------------- | -------------------------------------------------------------- |
| `./mvnw verify` (JDK 25.0.4.1+1)         | **PASS** — 57 unit, 116 integration, coverage gate met         |
| The same, on the GitHub runner           | **PASS** — same counts, gate met                               |
| JaCoCo over both suites                  | 80.5% lines (1168/1451), 70.0% branches (191/273)              |
| `bun scripts/dev/check-contracts.mjs`    | **PASS** — all three API documents                             |
| The same, against a broken document      | **FAILS and names the file**                                   |
| `bun scripts/dev/check-docs.mjs`         | **PASS** — 109 links across 36 files, 0 broken, 0 placeholders |
| `bun run format:check` (repository-wide) | **PASS**                                                       |
| PowerShell parse of `sf.ps1`             | **PASS** — 0 errors                                            |

### Decisions worth recording

- **`make replay` stays unimplemented, deliberately.** The shapes it would replay are generated by
  `make seed` today. Its own value is in replaying a scoring-service outage and a poison event, and
  neither exists to replay until the scoring client does. It lands with the pieces it demonstrates.
- **Rapid fan-in is not expressible**, and `DATA_PROVENANCE.md` says so rather than leaving a silent
  gap. `transactions` has no counterparty account column, and changing the schema to satisfy a
  generator would be the wrong way round.
- **The coverage gate stays at 0.70/0.60** despite measuring above both. Ratcheting twice inside one
  phase is churn; the next turn comes when Phase 4 finishes.

### Blockers

None.

### Next actions

Recorded in `PROJECT_STATE.md`: the feature pipeline, then ADR-0010 and reproducible training, then
the scoring endpoints and the Spring client.
