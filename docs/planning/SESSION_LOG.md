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

---

## Session 8 — 2026-08-26 — ADR-0010, and the reorder it forced

One decision, written before the training code for the same reason ADR-0008 was written before
either side of the boundary: four questions were about to be answered by whichever line of Python
got written first, and none of them is the kind of question a line of code should answer.

### The label source was genuinely undecided, not merely unwritten

`ScenarioType`'s own Javadoc already says planted shapes never enter the database, and that is the
right call — a label column on `transactions` is information that only exists after the fact sitting
next to the row a model is asked to score. But saying where labels do _not_ live leaves open where
they do, and the two obvious answers are both wrong.

Reimplementing the generator in Python was rejected because two definitions of six transaction
shapes drift, and the drift presents as a model that scores generated traffic well and live traffic
badly — a defect whose symptom points at the model and whose cause is in neither half.

Treating `analyst_feedback` as labels was rejected on a sharper ground: feedback only exists for
transactions the system already alerted on, so training on it learns the current threshold rather
than the fraud. It stays stored for later experimentation, which is what §12.6 asks for anyway.

What ships instead is an offline export in `apps/api` that runs the existing generator and writes
the exact `ScoreRequest` body the service would receive, plus the planted `ScenarioType`. One
generator, one definition, and the label lives in that file and nowhere else.

### The part that reordered the phase

**The export has to call the runtime's own account-context assembler**, and that is not a tidiness
preference. All sixteen features are computed from that context. An assembler that windowed,
ordered, capped or truncated even slightly differently at training time would produce train/serve
skew that **no metric in the evaluation report can detect** — because both the training and the test
halves of the comparison would be drawn from the training assembler, and they would agree with each
other perfectly while disagreeing with production.

So the assembler moved ahead of training, and the Spring scoring client will consume it rather than
writing a second one. `PROJECT_STATE.md` and `IMPLEMENTATION_PLAN.md` were both updated to say so,
because a plan that still listed the assembler with the client would send the next session down the
path this ADR exists to close.

### Calibration is a consequence of ADR-0008, not a preference

ADR-0008 §4 gives the API one alerting threshold that must mean the same thing under a model score
and under a rules-only degraded score. That is only coherent if the scale is stable across model
versions — so the estimator is fitted to be well calibrated, and Brier and a reliability curve are
reported rather than assumed. Without it, a threshold means one thing under logistic regression and
another under a boosted model, and promoting a model would silently re-tune the business's alert
volume with nobody changing the policy.

**That rules `IsolationForest` out of production before anything is trained.** Its anomaly score is
unbounded and dataset-relative, and giving it a stable meaning on a fixed scale means calibrating
against the labels it was included for being able to ignore. It stays as an unsupervised comparison,
which is the honest role for it, rather than being reported as a peer of the supervised candidates —
two different quantities printed to the same number of decimal places.

### The first draft of that section contradicted the contract, and reading the contract caught it

Worth recording as its own item, because nothing failed and no check would have.

The draft said the service "returns a calibrated probability in [0, 1]". The merged scoring
contract, from #31, already fixes `modelScore` as a number from 0 to 100 and states in the schema
that it is **not** a probability — because calling it one invites "87% likely to be fraud", which
synthetic planted-shape labels cannot support. CLAUDE.md makes contracts authoritative and forbids
an ADR quietly re-deciding one, and that draft would have done exactly that: not as an obvious
conflict a reviewer trips over, but as a decision document granting permission the contract does not.

The substance survived; only the units were wrong. What ADR-0008 actually needs is a **stable scale
across model versions**, and calibration is a property of the mapping, not of the units — a
calibrated quantity stays calibrated after a fixed monotone rescale onto 0–100. So the score is
calibrated underneath and served on the contract's scale, the positive class is named as "belongs to
a planted shape" rather than "is fraud", and the rejected alternative is written into the ADR so the
next reader sees that the scale was considered rather than overlooked.

### The selection rule was fixed before any number existed

Deliberately, so it cannot be rationalised afterwards. PR-AUC is the headline and accuracy is never
one. A model ships only if it beats the rules baseline by a stated margin — **and if none does, the
rules ship alone**, because having built a model is not a reason to serve one. A gap smaller than
the spread across the cross-validation folds is fold noise and goes to logistic regression. And the
operating point is chosen against an alert-volume budget rather than by maximising F1, because an
analyst team is a fixed-capacity queue and F1 optimises an arithmetic property of the confusion
matrix that nobody in operations experiences.

The split is group-disjoint on account **and** time-ordered, both, because neither subsumes the
other: grouping stops one planted burst's correlated rows landing on both sides of the cut, and the
time constraint stops training on traffic from after the test period.

### Tests and results — from runs on 2026-08-26

| Command                          | Result                                                         |
| -------------------------------- | -------------------------------------------------------------- |
| `bun run format:check`           | **PASS** — repository-wide                                     |
| `bun scripts/dev/check-docs.mjs` | **PASS** — 127 links across 37 files, 0 broken, 0 placeholders |

No code changed this session, so no test suite was run. Saying so is the point: this was a
documentation change, and reporting a suite it did not touch would be the kind of borrowed evidence
the evidence rule exists to stop.

### Blockers

None.

### Next actions

Recorded in `PROJECT_STATE.md`: the account-context assembler and the labelled export, then
reproducible training and the registry, then the scoring endpoints, the rules baseline and the
Spring client.

---

## Session 9 — 2026-08-26 — the assembler, a defect it uncovered, and the labelled export

Four pull requests. Two were what ADR-0010 §1 called for; the third was not planned and is the most
interesting.

### One assembler, because the second one is the defect

[#37](https://github.com/la3679/sentinelflow/pull/37). All sixteen features are computed from the
account context, so a training-time assembler that windowed, ordered, capped or truncated even
slightly differently from the runtime one produces train/serve skew — and skew of that kind is
invisible to every metric in an evaluation report, because both halves of the comparison come from
the training assembler and agree with each other perfectly while disagreeing with production. There
is no assertion that catches it afterwards. There is only not writing the second implementation.

Three properties are enforced by the query rather than by convention. The window ends **strictly**
before the scored transaction's own `occurredAt` — the API sends history as of when it asked, so a
replayed transaction legitimately carries rows from after itself — and the strict `<` is also what
excludes the transaction from its own history without needing its identifier, so one query serves a
persisted transaction and one that is not yet persisted.

Ordering breaks ties on `id`, which is load-bearing rather than tidy: with `occurredAt` alone, rows
sharing an instant come back in whatever order the plan produces, and once the result is truncated to
the cap, _which_ rows survive varies between runs. The same transaction would score differently on a
retry — an unreproducible score nobody would think to blame on an `ORDER BY`. The integration test
asserts it with every row at one instant.

Truncation is detected by asking for one row more than the cap. Exactly the cap is not reported as
truncated: it is a complete answer, and claiming otherwise would turn every downstream count into a
floor when it is exact.

**The balance is a parameter, and that seam is the one place ADR-0010's guarantee is narrower than it
reads.** `transactions` has no balance-after column, so a historical balance is not reconstructible
from the schema. Making it a parameter puts the obligation on the caller in the open rather than
letting an export inherit a wrong number silently.

### The off-hours shape had never been in the off hours

[#38](https://github.com/la3679/sentinelflow/pull/38), found while building the export and fixed
before any of it became a training label.

`OFF_HOURS_NEW_DEVICE` landed two hours after whatever time of day the run began. Measured, seed
20260826, profile CI: a midnight window start gave UTC hours 2 and 3; noon gave 14 and 15; 17:23:41
gave 19 and 21. The production caller passes `Instant.now()`, so midnight — the one start that made
it correct — essentially never happens. In every real seeded demo the planted "off-hours" transaction
sat at an ordinary hour and `is_off_hours` never fired on it.

Two guarantees were in conflict and one was unstated. Offsets from the window start are what make the
dataset reproducible and what the checksum covers; an off-hours shape is a _time of day_, which
cannot be expressed as an offset from an arbitrary instant. `generate()` now anchors the window start
to a UTC day boundary, so the precondition holds for every caller rather than for the ones who
remember it. Anchoring rather than computing an absolute target is deliberate: snapping to 02:00
would have made offsets depend on the hour a run started, and the same dataset would then hash
differently between runs.

**The existing test passed for the wrong reason**, which is the part worth keeping. It asserted
`offset().toHours() % 24` was between 2 and 3 — a property of the offset arithmetic, not of when the
transaction occurred — and the suite's fixture window began at midnight, where the two agree exactly.
The `@DisplayName` claimed the right property and the assertion checked a different one. It reads
`occurredAt` now, and a second test runs four window starts including 12:00, 17:23:41 and 23:59:59.

A test whose fixture happens to sit exactly where a defect is invisible is worse than no test: it
reports the property as checked.

### The labelled export, and the failure that would not announce itself

[#39](https://github.com/la3679/sentinelflow/pull/39). Labels are recovered rather than read, because
`ScenarioType` never enters the schema. The export regenerates from the same seed and joins to the
stored rows by idempotency key — derived from the seed, a sequence number and the transaction's
offset within the window, never from a clock — so an export run days later still lands every label on
its own row. `export()` therefore takes no instant at all: the window end is immaterial to the
output, and the signature saying so is better than implying it must match.

**A join shifted by one would produce a complete, well-formed, entirely mislabelled file.** The
trainer would run, the metrics would compute, and the model would simply be mediocre for a reason
nobody could attribute. Both halves therefore regenerate through one `ScenarioDataset` — two copies
of the same two queries are two chances for an `ORDER BY` to drift — and the integration test
re-derives the join backwards, line to `transactionId` to the stored row to its key. Two independent
routes to the same answer is the only check a shifted join fails.

It calls the runtime assembler, balance read included, which is exact rather than approximate because
**nothing in this application ever changes an account balance**. A test asserts that invariant
directly, so introducing balance mutation fails it and forces the export onto the parameter seam
rather than leaving a feature quietly wrong.

### Running it, rather than reading it

The compose wiring and the bind mount are exactly what no test covers, so the export was run against
the live stack. Seed 20260826, profile CI: the loader reported 242 generated, 242 written, 42
planted; the exporter reported 242 examples, 42 planted, dataset SHA-256 `91571d40…`.

`sha256sum` on the host matched the manifest, so the bind mount and the checksum agree. The
manifest's `scenarioChecksum` equalled the checksum the _loader_ logged at seed time, which is the
regeneration matching the load. The file itself: 0 context rows at or after their own transaction,
amounts strings throughout, 2 null devices present rather than omitted, and the two off-hours
examples at 02:17:08Z and 03:46:08Z — the previous commit's fix, correct through the whole pipeline.

### Tests and results — every figure from a run on 2026-08-26

| Command                                  | Result                                                         |
| ---------------------------------------- | -------------------------------------------------------------- |
| `./mvnw verify` (JDK 25.0.4.1+1)         | **PASS** — 63 unit, 152 integration, coverage gate met         |
| The same, on the GitHub runner           | **PASS** — same counts, gate met                               |
| JaCoCo over both suites                  | 81.6% lines (1315/1612), 71.8% branches (216/301)              |
| `bun scripts/dev/check-docs.mjs`         | **PASS** — 128 links across 37 files, 0 broken, 0 placeholders |
| `bun scripts/dev/check-contracts.mjs`    | **PASS** — every document                                      |
| `bun run format:check` (repository-wide) | **PASS**                                                       |
| PowerShell parse of `sf.ps1`             | **PASS** — 0 errors                                            |

Integration tests went 138 to 152 and unit tests 61 to 63. The new suites are
`AccountContextAssemblerIT` (14), `ScoringPayloadContractIT` (8), `TrainingDatasetExporterIT` (14)
and `ScoringContextPropertiesTests` (4), plus two generator tests.

### Decisions worth recording

- **ADR-0010 was corrected against the scoring contract before it merged.** The draft said the
  service "returns a calibrated probability in [0, 1]"; the merged contract fixes `modelScore` at 0
  to 100 and says it is not a probability. Nothing failed and no check would have caught it — a
  decision document reads as permission, so it would have been what the next implementation followed.
  Only the units were wrong; calibration is a property of the mapping, not of the units.
- **The coverage gate stays at 0.70/0.60** despite measuring above both, for the reason recorded last
  session: the next turn comes when Phase 4 finishes.
- **Two items are deliberately left for the training commit** rather than resolved quietly here: the
  `.gitignore` rule that ignores `apps/scoring/models/*.joblib` while ADR-0010 §6 says the artifact is
  committed, and the fact that `balanceDrainRatio` is measured against a balance that never moves, so
  an `ACCOUNT_DRAIN` reads as three independent partial drains rather than one cumulative emptying.
  Both belong in the model card and the evaluation document, where a reader would otherwise assume
  the opposite.

### Blockers

None.

### Next actions

Recorded in `PROJECT_STATE.md`: reproducible training with the registry and the model card, then
`/v1/score` and `/v1/model`, then the rules baseline, the Spring client and persisted assessments.

---

## Session 10 — 2026-08-26 — training, and four defects that only running it could find

Ended on an **emergency checkpoint** at the user's prompt that context was nearly exhausted. The
training pipeline was already committed and pushed before this entry was written; nothing was left
uncommitted.

Five pull requests across the session: ADR-0010 (#36), the account-context assembler (#37), the
off-hours generator fix (#38), the labelled export (#39), the phase checkpoint (#40), and the
training pipeline (#41).

### What landed

`make train` reads the labelled export, compares four candidates, applies the rule ADR-0010 §5 fixed
before anything was measured, and writes a registry entry with its manifest, metrics, plots and a
generated model card. Measured on the LOCAL profile: 20,707 examples, 707 planted, and a holdout of
2,499 rows carrying 75 positives. Logistic regression selected at PR-AUC 0.8327 against a rules
baseline of 0.1535.

**The features come from the serving extractor.** The loader parses each line back into a
`ScoreRequest` and calls `features.extract` — the same function `/v1/score` will call — and a test
asserts the _values_ match rather than the shapes. That is ADR-0010 §1 asserted rather than trusted.

### The four defects, and why none of them would have shown up in review

**The split produced an empty holdout, twice.** Positives are concentrated in a small minority of
accounts and each planted shape occupies a narrow window, so an unstratified account holdout
intersected with a time boundary is empty routinely rather than unluckily. Stratifying on "carries a
shape" was not enough either — all seven held-out positive accounts had their shapes before the
cutoff. It stratifies on "carries a shape _after the cutoff_" now.

Both failures were loud, because the split raises rather than reporting a recall over zero
positives. That was worth more than any amount of care: the alternative implementation would have
returned 0.0 and been believed.

**One threshold shared across candidates.** Two models can rank identically and place their scores
at completely different absolute values, so a threshold from one applied to another compares two
different alert volumes — which is the entire point of budgeting against a review capacity. Each
candidate takes its operating point from its own out-of-fold distribution now.

**A holdout of three positives.** The DEMO profile produces one. The selected model's PR-AUC moved
from 0.06 to 0.39 on the difference between finding one of them and none. Nothing about those
numbers was fabricated and publishing them would still have been dishonest — they would have been
presented as evidence while being incapable of supporting a conclusion. There is a floor of 20
positives now, below which nothing is promoted, and the card states the count either way so a reader
can judge for themselves.

**The model card printed `100.00` for a threshold of `99.99986221`.** No score reaches exactly 100,
so a reader applying `100.00` would have alerted on nothing at all. This is the one that would have
survived any review: the number was correct, the rounding was conventional, and the document was
wrong.

### The plots had to be looked at, not merely generated

The first reliability curve used quantile bins. With 97% of transactions scoring near zero, every
bin landed in that corner and the plot said nothing about the top of the scale — the only region an
operating point is ever in. Uniform bins fixed it, and markers are now sized by bin count so the
jagged middle reads as sparsity rather than as mis-calibration. A plot that is generated and never
opened is an artefact, not evidence.

### joblib warns on every model load, and it is on the serving path

joblib 1.5.3 assigns to `array.shape`, which NumPy 2.5.2 deprecated. Measured with and without
compression. When NumPy removes the behaviour the scoring service stops being able to load a model.
Silenced narrowly by message so `filterwarnings = ["error"]` survives everywhere else, and recorded
as an open item. ADR-0004 already pins the Python version to joblib's support window; this is the
second reason to watch it.

### Tests and results — every figure from a run on 2026-08-26

| Command                                        | Result                                         |
| ---------------------------------------------- | ---------------------------------------------- |
| `uv run pytest` (apps/scoring)                 | **PASS** — 94 tests, 96.75% coverage, floor 90 |
| `uv run ruff check`, `ruff format --check`     | **PASS**                                       |
| `uv run mypy` (strict)                         | **PASS** — 30 source files                     |
| `./mvnw verify` (apps/api, earlier in session) | **PASS** — 63 unit, 152 integration, gate met  |
| `bun run format:check`                         | **PASS** — repository-wide                     |
| `bun scripts/dev/check-docs.mjs`               | **PASS** — 128 links across 37 files           |
| `uv sync --no-dev` then import check           | matplotlib absent; sklearn and joblib present  |
| PowerShell parse of `sf.ps1`                   | **PASS** — 0 errors                            |

### Decisions worth recording

- **matplotlib is a training-only dependency**, so the serving image never carries a plotting library
  on the request path. Verified by installing as the image does rather than by reading the flag.
  R-2026-08-26-01.
- **pandas is deliberately not installed** although ADR-0004 anticipated it. The extractor produces a
  dict of floats and the model wants an array; ADR-0004's table pins a version to use if a need
  appears, and none has.
- **`.gitignore` lost its `models/*.joblib` rule**, because ADR-0010 §6 is later and binds. The rule
  would not have matched the nested entry path anyway, so the artifact was already being committed by
  accident — now it is by decision, with a command-enforced size ceiling.
- **The rules baseline in `apps/scoring` is a stand-in and says so.** When `apps/api`'s ruleset lands
  it must be replaced by scoring that ruleset, not kept alongside it.

### Blockers

None.

### Next actions

Recorded in `PROJECT_STATE.md`: `/v1/score` and `/v1/model` against the registry entry, then the
rules baseline in `apps/api`, then the Spring client and persisted assessments.

**The local database is on the LOCAL profile** — 20,707 transactions — because the evaluation needed
it. `PROJECT_STATE.md` records how to move back down, including that truncating without `users`
fails startup on `users_username_unique`.

---

## Session 11 — 2026-08-27 — the model becomes reachable

Phase 4's eleventh piece: `POST /v1/score` and `GET /v1/model` in `apps/scoring`, against the
registry entry session 10 trained. Three commits on `feat/scoring-inference-api`.

### What was built

**`serving/`, a package beside `training/` rather than inside it.** The dependency runs one way —
serving imports the registry and the score rescale, and nothing in training imports serving — which
is ADR-0010 §6's "training is a command, never an API side effect" made structural. `.claude/rules/python.md`
described a `models/` package that never existed; it now describes what is there, and says why
`models/` is not available as a package name.

**`POST /v1/score`** extracts features, runs inference, and returns the contract's fields and no
others. **`GET /v1/model`** publishes the manifest's identity and the selected model's holdout
figures, read from the metrics document beside the artifact rather than restated anywhere.

**Reasons are the linear model taken apart.** `coefficient x standardised value`, averaged across
the three calibrated folds, on the log-odds scale before calibration. That is not an approximation
of the model — for a logistic regression it is the model, which is the property ADR-0010 §5 chose it
for over a tree ensemble that scored no better than fold noise. Calibration is monotone, so the
contributions explain the ranking and deliberately do not sum to the 0-to-100 score.

### Three decisions inside the reason codes

- **An indicator is reported only when it fired.** `is_new_device` at 0.0 still has a standardised
  value and therefore a non-zero contribution, so the arithmetic would happily emit `NEW_DEVICE` for
  a transaction on a device the account has always used. That is not a weak explanation, it is one
  that says the opposite of what happened.
- **Direction describes the feature, not the contribution.** A below-average value with a negative
  coefficient pushes the score up; calling that `_HIGH` would tell an analyst the data said something
  it did not. `_HIGH` and `_LOW` come from the standardised value's sign.
- **A model that cannot be decomposed returns no reasons and says so.** An invented explanation is
  worse than an absent one, because only one of the two is visibly missing.

### Loading is mostly refusals, and one of them has no symptom

One entry serves, or the process does not start. The checksum and the feature version were already
checked; the column order was not, though the registry's own docstring claimed it was. That is the
one that matters most: a model handed its columns in a different order still returns a number, still
between 0 and 100, and it is an answer about different quantities. No error, no warning, nothing
downstream notices. `FEATURE_NAMES` now declares the order and `registry.load` requires the caller to
supply it — required rather than optional, because a check that has to be asked for is one that will
eventually not be.

Two entries at the running feature version are a refusal rather than a tie-break, because picking
either would make which model produced a score depend on directory iteration order. The escape hatch
is a configuration pin, and half a pin is rejected at startup.

An empty registry is deliberately **not** a refusal: the service runs, reports `modelLoaded: false`,
returns a retryable 503 from both endpoints, and the API degrades to rules. Refusing to start would
turn a designed degradation into an outage.

### Three defects found by running it

- **The training suite was overwriting `docs/ml/MODEL_CARD.md` on every run.** `--docs` defaults to
  the repository's own documentation tree and the end-to-end tests never passed it, so every
  `make test-scoring` replaced the published card with one describing the TEST fixture: 1,280
  examples where the real card records 20,707, a different profile, a different holdout, a different
  operating point. It went unnoticed because the file is generated and a regenerated generated file
  looks exactly like one. Found by `git status` after a test run, not by reading the test.
- **`/health/ready` returned `model_loaded` where the contract says `modelLoaded`.** It had been the
  only endpoint whose body was not camel case since Phase 1, and nothing checked response shapes
  against the contract — only request shapes. There is now a response-side conformance test, which
  is the reason the drift is worth more than its one-line fix.
- **The image never carried `models/`.** ADR-0010 §6 commits the artifact so a demo can score without
  a training run first, and the Dockerfile copied only the virtual environment — so the promise held
  everywhere except the place the service actually runs. `.dockerignore` still carried a
  `models/*.joblib` rule that expressed the opposite intent and never matched the nested path anyway.

### Tests and results — every figure from a run on 2026-08-27

| Command                                     | Result                                                        |
| ------------------------------------------- | ------------------------------------------------------------- |
| `uv run pytest --cov` (apps/scoring)        | **PASS** — 171 tests, 97.36% coverage, floor 90               |
| The same, before this session               | 94 tests, 96.75%                                              |
| `uv run mypy` (strict)                      | **PASS** — 0 issues, 42 source files                          |
| `uv run ruff check` / `ruff format --check` | **PASS**                                                      |
| `docker build apps/scoring`                 | **PASS** — 610 MB                                             |
| Container `/health/ready`                   | `{"status":"UP","modelLoaded":true}`                          |
| Container `/v1/model`                       | serves the committed manifest and its holdout figures         |
| Container `/v1/score`                       | 200, ten reasons, correlation id echoed; a bad body gives 422 |
| `/app/models` inside the image              | manifest, metrics, artifact — no plots, no card               |
| `bun run format:check`                      | **PASS** — repository-wide                                    |
| `bun scripts/dev/check-docs.mjs`            | **PASS** — 141 links across 40 files                          |
| `bun scripts/dev/check-contracts.mjs`       | **PASS** — all three API documents                            |

Every module under `serving/` is at 100% statement and branch coverage; `training/registry.py`
reached 100% with the discovery and metrics-reading tests.

### Blockers

None.

### Next actions

Recorded in `PROJECT_STATE.md`: the rules baseline in `apps/api`, then the Spring scoring client with
its timeouts, bounded retry and circuit breaker, then persisted assessments and `make replay`.

---

## Session 12 — 2026-08-27 — the rules baseline, and a floor that was in the wrong place

Phase 4's twelfth piece: the transparent ruleset in `apps/api`, and the discovery that the thing
every model had been measured against was not it. Four commits on `feat/rules-baseline`.

### What was built

**Seven indicators, in the service that has to run them.** Velocity over five minutes, the amount
against this account's own recent mean, a device the account has not used, a country change, the
small hours, a large share of the balance, and distinct merchants within the hour. Each contributes a
configured weight; the sum is clipped to the contract's 0-to-100 scale and returned with the reasons
that produced it, sourced as `RULE`.

They live in `apps/api` because ADR-0002 §3 says so and ADR-0008 §3 explains why: a ruleset reached
over the network could not answer "the network is down". Thresholds and weights are configuration
validated at startup (§8.4); which indicators exist is code, because an indicator has a definition
and a definition is not a number.

**Invalid configuration fails the context rather than falling back**, unlike `ScoringContextProperties`
which clamps. The difference is deliberate and worth stating: a clamped lookback window still produces
a defensible context, where a negative weight produces a score silently wrong in a direction nobody
chose. A service that will not start is a problem an operator can see.

### The finding: the floor was in the wrong place, and it was too low

`training.evaluation.rules_baseline_scores` was a Python stand-in. It said so in its own docstring and
the previous session recorded it as an owed item. The obvious move was to reimplement `apps/api`'s
ruleset in Python — which is exactly the mistake ADR-0010 §1 already rejects for the account
assembler, with the same failure mode: two implementations drift, and the drift presents as a model
beating a baseline nobody runs.

So the stand-in was **deleted rather than replaced**. `TrainingDatasetExporter` evaluates every
example with `RuleEngine` — the same engine, on the same assembled request, that the API runs when
scoring is unreachable — and `ruleScore` became a fourth field on each exported line beside `label`.
The trainer reads the column. There is one ruleset, and the comparison is against it by construction
rather than by care.

**Then the numbers moved.** On the same holdout the stand-in scored PR-AUC 0.1535; the shipped
ruleset scores 0.2611, with precision 0.722 against 0.542 and a false-positive rate of 0.0021 against
0.0045. Every margin over "the rules" published before today was measured against something weaker
than the rules, by roughly 0.11 PR-AUC.

The conclusion did not change. Logistic regression still scores 0.8327 — same features, same split,
same seed, byte-identical artifact — and still clears the 0.05 margin, now by 0.57 rather than 0.68.
`EVALUATION.md` records the correction rather than quietly carrying the new number, because the point
of a limitations section is the things it admits.

**The rule score is a comparison column and never a feature.** It is not part of `ScoreRequest`, so
the extractor cannot see it; a model trained on it would be partly modelling the rules. An integration
test asserts its absence from both request halves rather than leaving that to a reader.

One smaller correction fell out: the baseline's operating point now comes from the training rows,
exactly as every candidate's does. It previously came from a fresh computation over the same rows it
reported on, which gave the floor the one advantage the models are denied.

### Two defects the ruleset found by being run

- **`NEW_DEVICE` fired on an account with no history at all.** "Not one of the account's known
  devices" is trivially true when there are none, so the rule put fifteen points on the first
  transaction of every account that had been quiet for a day — most low-activity accounts on most
  days. Caught by a test asserting an account with no history is not suspicious. It now needs
  something to compare against, which is the same principle that leaves the amount ratio and the
  country change without a default.

  This is a **deliberate** difference from the model feature of the same name, which does report 1.0
  on an empty history. The model sees `history_size` beside it and learns what the pair means
  together; a rule asserts a fixed weight with nothing beside it, so it has to carry the
  qualification itself. Both files say so.

- **Reasons needed a tie-break.** `COUNTRY_CHANGE` and `NEW_DEVICE` both weigh 15, so ordering by
  contribution alone left their order to whichever ran first. A persisted assessment whose reason
  order moved between identical runs would be unreproducible for no reason at all.

### The stack was found in a crash loop

`docker compose ps` reported the API `Restarting (1)`. `SENTINELFLOW_SCORING_EXPORT_ENABLED` was still
set on the service from the previous session's `make export-dataset`, and the export runner correctly
refuses to overwrite an existing dataset — so the container failed startup, restarted, and failed
again. It had been doing that since the last session. Nothing else was affected and no data was lost.

The Makefile target does recreate the service without the flag afterwards, so that second recreate did
not take effect or was interrupted. **Check `docker compose ps` before assuming the stack is healthy.**
Recorded in `PROJECT_STATE.md` as something to harden when `make replay` is written, since it will
need the same dance.

### Tests and results — every figure from a run on 2026-08-27

| Command                                          | Result                                                                                        |
| ------------------------------------------------ | --------------------------------------------------------------------------------------------- |
| `./mvnw verify -DskipITs` (JDK 25.0.4.1+1)       | **PASS** — 91 unit tests, 28 of them the ruleset's                                            |
| `./mvnw verify -DskipUnitTests=true`             | **PASS** — 154 integration tests, JaCoCo gate met                                             |
| `uv run pytest --cov` (apps/scoring)             | **PASS** — 171 tests, 97.36% coverage, floor 90                                               |
| `uv run mypy` (strict)                           | **PASS** — 0 issues, 42 source files                                                          |
| `make export-dataset` (profile LOCAL)            | 20,707 examples, 707 planted, sha256 `8eb1bac8…`                                              |
| `uv run python -m sentinelflow_scoring.training` | rules 0.2611 · logistic 0.8327 · boosting 0.8081 · iforest 0.7154                             |
| Rule-score distribution over the export          | 23% of NORMAL fires; 100% of OFF_HOURS_NEW_DEVICE, 53% of VELOCITY_BURST, 52% of CARD_TESTING |
| `bun run format:check`                           | **PASS** — repository-wide                                                                    |
| `bun scripts/dev/check-docs.mjs`                 | **PASS** — 141 links across 40 files                                                          |
| `bun scripts/dev/check-contracts.mjs`            | **PASS** — all three API documents                                                            |

The ruleset is a real floor rather than a formality: it separates planted shapes from background
traffic without being anywhere near good enough to make a model pointless.

### Blockers

None.

### Next actions

Recorded in `PROJECT_STATE.md`: the Spring scoring client with its timeouts, bounded retry and
circuit breaker, then persisted assessments, then `make replay`.

**One mismatch to resolve before assessments are persisted.** `risk_assessments.reason_codes` is a
`List<String>` on the entity, while `contracts/schemas/common.v1.json` and the API's `ReasonCode` are
objects carrying `description`, `contribution` and `source`. `RuleReason` already produces all four.
Which the column holds should be decided once, with the contract, the entity and the event changed
together.

---

## Session 13 — 2026-08-27 — the scoring client, and an ADR that argued for a property it did not have

Phase 4's thirteenth piece: `ScoringClient` with ADR-0008 §3's resilience, and ADR-0011 deciding what
a rule score and a model score combine into. Two commits on `feat/scoring-client`.

**This session was stopped at a checkpoint rather than finished.** The workflow that joins the
ruleset, the client and the policy into a persisted assessment is designed and specified and not
written. Everything below is landed and green.

### What was built

**`ScoringClient`.** Posts an assembled `ScoreRequest` to `/v1/score` with a 1 s connect and 2 s read
timeout, two retries with full jitter, and a circuit breaker. Three outcomes, deliberately not
interchangeable: a score; `ScoringUnavailableException`, from which the caller degrades to rules;
`ScoringRejectedException`, which the caller dead-letters. Collapsing the last two into "scoring
failed" is the mistake the class exists to prevent.

**The breaker counts only unavailability.** A service answering 422 in a millisecond is not sick.
Opening on it would turn every later transaction into a degraded assessment and hide the contract
mismatch behind a system that still appears to work. Hand-written rather than taken from a library:
one threshold, one timer and three states, against a decision already made in an ADR that a library
would make us restate in its own terms. Consecutive failures rather than a rolling rate, because a
rate window either opens on a quiet minute or refuses to open under a flood.

**`FullJitterBackOff` moved to a new `resilience` package and became public.** It has two callers now
— a listener retrying a delivery, this client retrying a call — and both are a thread sleeping before
it tries the same thing again. Copying it to avoid the move would have been the drift its own comment
argues against.

### The budget was a sentence; it is now a startup validation

ADR-0008 §3 says the whole call is under ten seconds "by construction". Making that a real check
needed the jitter counted properly: the obvious estimate, `ceiling x retries`, adds two seconds and
puts the ADR's own numbers at 11 s — over its own limit. The schedule's windows for two retries are
actually 200 ms and 400 ms, so the true worst case is 9.6 s.

`FullJitterBackOff.worstCaseTotalDelay` computes it where the schedule is defined, so the check and
the behaviour cannot disagree. A test pins the distinction, because the naive version fails silently
in the safe direction — it would just refuse configurations that are fine.

### The finding: ADR-0011's first draft claimed a property its own formula does not have

The draft rejected `max(rule, model)` partly because "two independent signals both at 60 should not
land in the same place as one at 60 and one at 0". A test written straight from that sentence failed:
under `max(rule, 0.6 x model + 0.4 x rule)`, `combine(60, 60)` and `combine(60, 0)` are **both 60**.
The floor discards corroboration exactly as `max` does.

The formula was kept and the ADR was corrected rather than the test. The defence is better than the
original claim: the model's features and the rules' indicators are computed from the same account
context, so they are not independent observations, and treating agreement as corroboration would
count one observation twice. **The rules set a floor and the model escalates above it.** A model can
raise a score and never lower one — one sentence, and an analyst can hold the whole policy in it.

The ADR records the wrong claim, the failing test and the defence, rather than presenting the
conclusion as though it had been obvious from the start.

### `reason_codes` had been the wrong shape since Phase 2

`risk_assessments.reason_codes` was mapped as `List<String>` while
`contracts/schemas/common.v1.json` and `sentinelflow-api.yaml` had always described an object — a
code, a description, a contribution and a source. Nothing had noticed because **nothing wrote the
column**. The first write would also have been the first time the two had to agree, which is to say
the mismatch was scheduled to be discovered by the feature that depended on it.

`jsonb` is why the fix needed no migration, and also why it went unseen for two phases. The entity is
now `List<ReasonCode>`, and `EntityMappingIT` round-trips the objects through PostgreSQL rather than
asserting the intent.

`ReasonCode.noIndicators()` came out of the same reading. The column's `CHECK` requires at least one
reason and its own comment says an assessment with no reason cannot be defended to anyone — but a
transaction that trips nothing is the _ordinary_ case, so an assessment for one would have violated
the constraint on the first quiet transaction. "The ruleset examined this and found nothing" is an
explanation; an empty array is the absence of one.

`alerts.top_reason_code` has the identical mismatch and is deliberately untouched: alerts are Phase 5,
nothing writes that column either, and it should be settled there in one change across the contract,
the entity and the event. Recorded in `PROJECT_STATE.md`.

### Tests and results — every figure from a run on 2026-08-27

| Command                                    | Result                                            |
| ------------------------------------------ | ------------------------------------------------- |
| `./mvnw verify -DskipITs` (JDK 25.0.4.1+1) | **PASS** — 132 unit tests, 41 of them new         |
| `./mvnw verify -DskipUnitTests=true`       | **PASS** — 158 integration tests, JaCoCo gate met |
| `bun run format:check`                     | **PASS** — repository-wide                        |
| `bun scripts/dev/check-docs.mjs`           | **PASS** — 146 links across 41 files              |

The client is tested against a real socket rather than a mocked `RestClient`: a read timeout, a
refused connection and a 2xx with an empty body only exist at the transport, and a mock would have
passed while the shipped timeouts were attached to nothing. `apps/scoring` was not re-run; nothing in
it changed this session.

### Blockers

None.

### Next actions

Recorded in `PROJECT_STATE.md`: `RiskAssessmentService` and the handler that drives it, then
`make replay`, then Phase 4's gate. The three client outcomes map straight onto ADR-0008 §2's table,
and `ScoringRejectedException` has to become a `NonRetryableEventException` so a contract mismatch
dead-letters rather than quietly degrading.

---

## Session 14 — 2026-08-27 — the workflow, and three defects that were invisible until the stack ran it

Phase 4's last two pieces: `RiskAssessmentService`, the handler that drives it, and `make replay`.
Eight commits on `feat/assessment-workflow`.

**The headline is not the workflow.** It is that running the finished pipeline against the local
stack — rather than against Testcontainers, which is all any previous session had done — found three
defects that every suite in the repository was green through, and one of them meant no transaction
could be scored at all.

### What was built

**A contract correction, before anything used it.** `common.v1.json` described `reasonCode.contribution`
as "points this factor contributed to the final score", bounded to ±100. True of a rule, where the
contribution is the configured weight and the reasons sum to the rule score. False of the model,
whose contribution is `coefficient x standardised value` on the log-odds scale before calibration —
it explains the ranking and sums to nothing, which the scoring contract already said in its own
words. The bound went with the description rather than being kept as a safety net: a log-odds
contribution has no natural ceiling, so honouring a bound means clamping, and clamping changes a
number an analyst reads without saying so.

**`ruleset_version`, which had nowhere to go.** V4 gave `risk_assessments` a model version, a feature
version and a policy version and no column for the ruleset. `RuleOutcome`'s Javadoc said the ruleset
version is "persisted on the assessment", the labelled export records it in its manifest, and the
configuration comment promises it moves whenever a weight or a threshold does. Nothing had noticed,
for the same reason the `reason_codes` shape mismatch went unnoticed for two phases: nothing had ever
written the table, so no code had been forced to put every version it depends on somewhere.

V8 adds it NOT NULL with no default and no backfill, and refuses loudly with an explanation if it
ever finds a row — there is nothing to backfill, and a default would be a fabricated version attached
to rows nobody can attribute. It is not nullable on a degraded assessment either, which is the point
of putting it beside `policy_version` rather than beside `model_version`: the rules are the half that
always runs, and a degraded assessment is made of nothing else.

**`RiskAssessmentService`.** Assembles the request, evaluates the ruleset in process, calls the
scoring client, combines through `RiskPolicyProperties`, bands the result and writes the row — with
the transaction's move to `ASSESSED` and the `risk.assessed` outbox row in the same database
transaction as the ledger row that records the event as handled. Three outcomes straight off
ADR-0008 §2's table, and `ScoringRejectedException` is deliberately not caught: absorbing a contract
mismatch as a degraded assessment hides a defect behind a dashboard that still looks healthy.

**Reasons are grouped by source, not sorted as one list.** A rule weight of 10 and a log-odds
contribution of 1.2 are not comparable magnitudes, and interleaving them by size ranks them against
each other on the strength of a comparison that means nothing. Rules lead, because they are the half
an analyst can check. The ordering is applied in the service rather than trusted from the
`RuleOutcome` — the same argument `RuleEngine` makes about re-windowing history, and found by a test
that supplied its reasons unsorted and got them back that way.

**`ScoringTransactionCreatedHandler`.** The first implementation of the port Phase 3 left open, and a
translation and nothing else: it turns the service's three outcomes into the two answers a Kafka
consumer understands. The translation lives there and not in the service because "dead letter" is a
delivery concept, and Phase 5's rescoring endpoint will call the same method from an HTTP request
that has no partition to block.

### Registering the first handler broke a delivery test, which is the useful part

`TransactionCreatedConsumerIT` asserted that a successfully handled transaction stays `PENDING`. That
stopped being true, because scoring correctly moves it to `ASSESSED`. The suite's subject is
delivery — deduplication, retry classification, dead-lettering — so the scoring handler is replaced
there with a no-op rather than the assertion being rewritten to describe the risk workflow. That is
exactly the coupling `TransactionCreatedHandler` exists to prevent, showing up the first time it
could.

### Three defects that only running the stack could find

**Nothing created the Kafka topics.** ADR-0006 §3 decided it — "topics are created explicitly, never
by broker auto-creation, which is disabled in `compose.yaml`" — the setting was applied, and the step
in between was never built. The stack came up with all seven services healthy and no message could be
published on it: `UNKNOWN_TOPIC_OR_PARTITION`, once a second, for ever, behind a green API. It
survived three phases because Testcontainers auto-creates. A one-shot `kafka-topics` service now
creates all five before the API starts, with three partitions on the operational topics — on a
single-partition topic every ordering assertion passes for the wrong reason.

**The scoring client asked uvicorn to speak HTTP/2.** With the topics fixed, the relay drained a
backlog of 20,073 outbox rows and every single scoring call was rejected: 13,455 assessments written
degraded, 6,224 events dead-lettered, not one model score. The scoring service's log carried an
"Unsupported upgrade request" warning for every "request rejected" line — 7,240 against 7,242.

The JDK's `HttpClient` defaults to `HTTP_2`, which against an `http://` URI means every request
carries `Upgrade: h2c`. uvicorn serves HTTP/1.1 only: it refuses the upgrade, fails to read the body
that came with it, and answers 422 naming the whole body as invalid. The client then correctly
classified that as a rejection and correctly dead-lettered it — the system working exactly as
designed on top of a request that should never have been sent, which is also why the failure was
visible within minutes rather than showing up later as a model that mysteriously never contributed.

Pinned to HTTP/1.1. The test asserts the **wire** rather than the setting: `ScoringClientTests` and
`RiskAssessmentWorkflowIT` were both green throughout, because `com.sun.net.httpserver` answers an
upgrade attempt by ignoring it — which is precisely the difference between a stub and the thing it
stands for. The new assertion is that no request carries an `Upgrade` header at all, verified by
removing the fix and watching it fail.

**Two PowerShell targets had never worked.** `Invoke-NativeCapture` takes no working directory, and
`Invoke-Seed` and `Invoke-ExportDataset` had been calling it with one since they were written, so
both died on "a positional parameter cannot be found". A `(?m)^api$` match never matched either,
because the anchor sits after the carriage return the platform leaves on the line. Both found by
running `.\scripts\dev\sf.ps1 replay` rather than by reading it.

### `make replay`

The operational scenarios from §8.3 that nothing else produces. The transaction shapes are `make
seed`'s and replaying them again would be a second implementation; the HTTP replay endpoint is API
surface that needs authorization and rate limiting, and shipping an unbounded one to satisfy a
Makefile target would be the wrong trade. The script says both rather than leaving the gaps to be
discovered.

The outage scenario stops the scoring container, posts transactions, prints the degraded assessments,
restarts it, waits out the breaker's open window and prints the scored ones. The poison scenario
publishes **two** records, because there are two outcomes and only one is a dead letter: a well-formed
envelope at an unsupported schema version is dead-lettered, while a record that is not a readable
envelope at all is deliberately not — the DLQ schema requires a valid envelope and ADR-0006 §4 forbids
copying unsanitised content onto an operational topic, so it is counted and logged instead. The first
draft published only the second kind and then waited for a dead-letter record that correctly never
came.

`compose.yaml` also now waits for scoring with `service_started` rather than `service_healthy`.
Readiness is 503 when no model is loaded, so a registry the image could not serve stopped the API
from starting at all — the opposite of ADR-0008 §3, where an unreachable scoring service is what the
degraded path exists for.

### Tests and results — every figure from a run on 2026-08-27

| Command                                        | Result                                                         |
| ---------------------------------------------- | -------------------------------------------------------------- |
| `./mvnw verify` (JDK 25.0.4.1+1)               | **PASS** — 147 unit tests, 172 integration tests               |
| JaCoCo, both suites                            | 85.7% lines (1794/2093), 76.9% branches (362/471)              |
| `bun scripts/dev/check-contracts.mjs`          | **PASS** — every schema, example and API document              |
| `bun scripts/dev/check-docs.mjs`               | **PASS**                                                       |
| `bunx prettier --check`                        | **PASS** — every file touched                                  |
| `./scripts/dev/replay.sh` (both scenarios)     | **PASS** — 4 degraded, then 4 scored; DLQ +1; undeliverable +1 |
| `.\scripts\dev\sf.ps1 replay` (both scenarios) | **PASS** — same outcomes on the reference Windows path         |

Coverage ratcheted to LINE 0.80 and BRANCH 0.70, from 0.70 and 0.60. `apps/scoring` was not re-run;
nothing in it changed this session.

**Correction, same session.** Two commit messages on this branch — the h2c fix and the handler — quote
"146 unit tests". The figure was correct when the handler landed and stale by one when the h2c fix
added its own regression test; the true count on the branch tip is 147. Recorded here rather than
rewritten into the commits, because this repository does not rewrite published history. Every other
figure in those messages stands.

### Blockers

None.

### Outcome

Merged as [#47](https://github.com/la3679/sentinelflow/pull/47) with all ten required checks green.
Phase 4 is closed against its gate: training reproducible from a documented command, evaluation
report generated, model checksum and version stored, and service contracts and failure behaviour
tested — the last of which needed exactly this workflow, since until now nothing covered what the
pipeline did with the client's three outcomes.

### Next actions

Recorded in `PROJECT_STATE.md`. Phase 5 opens with alert creation attaching to a band that already
exists and is already persisted, and settling `alerts.top_reason_code` in the same change.

---

## Session 15 — 2026-08-27 — Phase 4 closed, and Phase 5 opened as far as a checkpoint allowed

Two merges and one branch left open. Phase 4's gate closed as [#48](https://github.com/la3679/sentinelflow/pull/48);
Phase 5's first piece is written, unit-tested and pushed on `feat/alert-creation` with **no
end-to-end coverage**, which is why there is no pull request for it.

**This entry is written at a checkpoint rather than at a finish line.** The context budget reached
its threshold mid-way through the alerting work, so the rule in `CLAUDE.md` applied: stop new
feature work, save state, commit what is safe, push.

### Phase 4's gate

All five criteria met, with the evidence named: `make train` from a fingerprinted export at a
recorded seed, a generated evaluation report and model card, an artifact checksum verified before
every load, contracts tested in both directions, and failure behaviour covered by
`RiskAssessmentWorkflowIT` against real PostgreSQL and Kafka. The last one is what needed the
workflow — until it existed, `ScoringClientTests` covered the client's three outcomes and nothing
covered what the pipeline did with them.

The gate section also records what a gate does not cover. Three defects in that phase were invisible
to every suite and were found by running the compose stack.

### Phase 5, as far as it got

**The alerting rule joined the policy object** rather than starting a second one, because ADR-0008
§4 already calls it "the alerting policy applied to a final score at runtime". `alertFromBand` is
monotone in severity by construction; `priorityByBand` is separate because the band describes the
score and the priority describes the queue; both halves of its validation are refusals at startup.
`policy.version` moved to 1.1.0 because what it describes changed.

**V9 adds `alert_reference_seq`.** Four digits caps it at 9,999, and unlike the transaction
reference that is a ceiling this project can plausibly reach. Left loud rather than widened: `NO
CYCLE` turns exhaustion into a refused INSERT naming the sequence, which is a legible signal that
the alerting policy is producing more alerts than any review capacity could absorb.

**`AlertRaiser` writes three rows in the assessment's own transaction.** The summary is built from
the leading reason **code** and never its generated description — a description legitimately names a
device handle or an amount ratio, which is right on a detail page an analyst has opened and wrong on
a queue row and in an event that leaves this service.

### The model alone cannot raise an alert, and nobody had written that down

ADR-0011's combination is `max(rule, 0.6 x model + 0.4 x rule)`. With a rule score of zero the best a
perfect model can produce is 60, and the alerting band starts at 70 — so a transaction that trips no
transparent indicator can never open an alert, however confident the model is. The smallest rule
score that lets a maximal model reach the band is 25: exactly one rule firing.

Two decisions that were each defensible alone produce it, and it was not visible until they met. It
is not obviously wrong — "we only alert when at least one indicator an analyst can read has fired" is
a defensible policy for an explainability-first console, and it is arguably the point of the floor.
But it is currently an accident rather than a decision, and it is recorded in `PROJECT_STATE.md` as
the second of the next three actions so it gets taken deliberately. Changing a number now would be
re-deciding an ADR without the alert-volume evidence that ADR-0011 itself asks for.

### A correction to `PROJECT_STATE.md`

That file said `alerts.top_reason_code` was "a string on the entity while
`contracts/schemas/alert-created.v1.json` describes an object", and listed settling it as Phase 5
work. There is no such column and no such entity field: `topReasonCode` exists only on the event, and
the schema has always described it as a `reasonCode` object. The payload derives it from the
assessment at publication time. Corrected in that file, noted here, and the repository trusted over
the state document as `CLAUDE.md` requires.

The schema's description of that field was corrected for a different reason: it called it "the single
largest contributor", which is not well defined across a rule's 0-to-100 weight and a model's
log-odds decomposition.

### Tests and results — every figure from a run on 2026-08-27

Recorded in `PROJECT_STATE.md` under the Phase 4 gate and beside the in-progress section. The
alerting work is covered by unit and contract tests only; the integration test is the next action and
its absence is the reason no pull request was opened for it.

### Blockers

None.

### Next actions

Recorded in `PROJECT_STATE.md`: the integration test for the alert path, then deciding the "model
alone cannot alert" question deliberately, then the rest of Phase 5.

---

## Session 16 — 2026-08-28 — the alert path covered end to end, and an accident turned into a decision

Resumed on `feat/alert-creation` with a clean tree in sync with the remote, and continued from the
first incomplete item in `PROJECT_STATE.md` rather than restarting anything. Two commits, pushed, and
the branch opened as a pull request.

### The integration test the last session owed

Five cases in `RiskAssessmentWorkflowIT`, against the same real broker and database the rest of that
suite uses: the alert itself, its summary, its attribution, its `alert.created` event, and what a
redelivery does. The existing scored-path test gained an assertion that no alert row exists, because
`alert_raised: false` is only half a claim until something checks the other half.

**No scoring stub can provoke an alert on its own**, which is the arithmetic below arriving as a
practical obstacle in the first test that needed one. The fixture builds a history the ruleset reacts
to: four transactions inside five minutes fire `VELOCITY_5M_HIGH` for 25, a fifth originating
elsewhere adds `COUNTRY_CHANGE` for 15, and 40 against the stub's 92.5 combines to 71.5 — HIGH, at
HIGH priority.

**The instants are fixed rather than `now()`, and that is not fastidiousness.** Two of the seven
rules read the clock: the velocity window is five minutes wide, and `OFF_HOURS` fires between 02:00
and 04:59 UTC. A history built from `now()` would have scored 40 by day and 50 overnight, so a suite
asserting 71.50 would have passed every run except the ones that started in the small hours — the
worst possible failure shape, because it looks like flakiness and is arithmetic.
`SchemaFixtures.insertTransactionFrom` takes the occurrence instant for that reason and now has its
first caller.

One merchant across the whole history, deliberately: a fresh merchant per row would fire
`DISTINCT_MERCHANTS_1H_HIGH` too, and the test states its arithmetic exactly rather than asserting a
band and hoping.

### "The model alone cannot raise an alert" is now ADR-0011 §4

The previous session found it and deliberately left it undecided. It is now a stated policy: with a
rule score of zero the combination caps at `modelWeight x 100 = 60` and `HIGH` starts at 70, so a
transaction that trips no transparent indicator cannot open an alert however confident the model is.

**Stated rather than dissolved.** The console is explainability-first, so every alert it raises can
be opened on an indicator an analyst can check and dispute; an alert justified only by "the model was
confident" is the one they learn to clear without reading. The cost is written into the same section
rather than left out of it — a shape the model recognises and the ruleset misses is scored, banded,
persisted and visible, and opens nothing. That is real, and it is accepted rather than overlooked.

Lowering `alertFromBand`, raising `modelWeight` or removing the floor would each make it go away, and
each would be re-deciding a section of that ADR without the measured alert volume the ADR already
says those numbers should be revisited against. `sentinelflow.alerts.raised` is tagged by band and
priority so the evidence can exist. The "revisit if" gained the condition that would reopen it, and
the route back is named: a new rule that makes the shape transparent, or a superseding ADR that says
plainly that alerts may rest on the model alone.

Two tests in `RiskPolicyPropertiesTests` pin both halves — a maximal model over a silent ruleset
bands MEDIUM, and 25 is where the combination first clears 70 — so moving either number cannot alter
the implication silently. The same reasoning sits in `application.yaml` beside `alert-from-band`,
which is where an operator changing that number will actually look.

### Tests and results — every figure from a run on 2026-08-28

Recorded in `PROJECT_STATE.md`. 156 unit tests, 181 integration tests, every coverage check met, and
line coverage back to 87.0% from the 83.4% the previous session recorded as the honest cost of an
untested `AlertRaiser`. Nothing was written to move a coverage number; the figures moved because the
class is now executed by tests that assert what it wrote.

Still not demonstrated on the compose stack, and that is stated rather than glossed: the previous
session found three defects that every suite was green through.

### Blockers

None.

### Next actions

Recorded in `PROJECT_STATE.md`: merge the pull request, then the alert state machine with optimistic
concurrency on `alerts.version` beside it, then assignment, notes, feedback and role authorization.

---

## Session 16, continued — 2026-08-28 — the investigation state machine, and the authentication it needed

Four commits on `feat/alert-state-machine`, opened as [#50](https://github.com/la3679/sentinelflow/pull/50)
after [#49](https://github.com/la3679/sentinelflow/pull/49) merged with all ten checks green.

### The order was chosen rather than inherited

The build prompt's likely-commit list puts the state machine before authentication, and the
implementation plan lists ADR-0012 among Phase 5's deliverables without ordering the two. The
transition endpoint needs a principal, so the sequence taken was: the graph, then the service with
the actor as a parameter, then authentication, then the endpoint. The middle step is what makes the
order work — a service that takes an actor is the same code whether a person holds a token or the
pipeline is acting, and it was covered against a real database before any of the security code
existed.

### Three things about the graph worth keeping

**Which moves exist is not configuration.** Thresholds are numbers on their own schedule and belong
in `application.yaml`; this is a definition of what an investigation is, and a stack configured to
allow `CLOSED → NEW` would produce an audit trail no other stack could reproduce.

**Terminal means terminal, and it is load-bearing.** `Alert.transitionTo` clears `closed_at` on a
move to a live state because the CHECK requires a live alert not to carry one — so a legal move out
of a terminal state would erase when the investigation ended. Reopening is not a transition; it
would be a new alert citing the same assessment.

**Three property tests carry more weight than the edges.** No self-transitions, which the
`alert_actions` CHECK would refuse at commit; no outgoing move from a terminal state; and a
breadth-first search proving no live state can be stranded. Each catches a change that would satisfy
every edge assertion and still break something.

### The concurrency check is made twice, on purpose

`expectedVersion` is compared against the loaded alert, and `@Version` is compared again at flush.
The first is for an analyst working from a stale read and can name the version the alert is actually
at; it is a read followed by a write, so `@Version` on the UPDATE is what makes the loser of a real
race lose. Both answer 409, because from the caller's side they are the same thing.

The flush is also not tidiness: `alert-updated.v1.json` requires the version **after** the change,
and that value does not exist until the UPDATE is written. Adding one to what was in memory would be
a second implementation of the provider's counter.

### ADR-0012, and the gap it leaves on purpose

A password for a thirty-minute bearer token, verified from its signature alone. Credentials in their
own table, because the system principal must never authenticate and the absence of a row makes that
structural rather than a rule somebody remembers. Demo operators come from the application seed with
a password `make bootstrap` generates, never from a migration.

`POST /api/v1/transactions` stays open. It is a machine-to-machine surface whose caller is a payment
pipeline rather than a person, so an operator's password buys nothing there; it needs its own
credential with the rate limits and payload bounds beside it, which is Phase 8's. Written into
ADR-0012 §5, the README's stated limitations, and `PROJECT_STATE.md`'s known issues — three places,
because a security gap that is only in a commit message is a gap nobody will find.

### Four things found by running rather than reading

- **An auditor's token produced a 500 rather than a 403.** `@PreAuthorize` throws inside the
  handler, so the dispatcher sees `AccessDeniedException` before Spring Security's
  `ExceptionTranslationFilter` does, and it fell through to the catch-all.
- **The security configuration broke every schema test.** A `SecurityFilterChain` needs
  `HttpSecurity`, which does not exist under `webEnvironment = NONE`. The password encoder and the
  JWT encoder and decoder are needed by the seed and the login service and have nothing to do with
  HTTP, so they now live apart from the filter chain.
- **A fixture that passed alone and failed behind another suite.** `OperatorAuthenticationIT` seeded
  its operators, and the seed skips a database that already has parties in it — so it passed on its
  own and failed whenever anything had written a customer first. It creates its own operators now.
- **`/actuator/env` answers 401 rather than 404.** The chain refuses before the actuator decides
  whether the endpoint exists, which discloses less. Both smoke scripts and the application test were
  updated to expect it; neither script has been run against a live stack yet, and that is recorded.

### Tests and results — every figure from a run on 2026-08-28

172 unit tests, 211 integration tests, every coverage check met, 88.3% lines and 78.1% branches.
Contracts, documentation links and repository-wide formatting all pass. Nothing has been
demonstrated on the compose stack this session.

### Blockers

None.

### Next actions

Recorded in `PROJECT_STATE.md`: merge #50, then assignment, notes and analyst feedback, then the
reporting endpoints and the CSV export. And `make smoke` on a machine with the stack up, because the
actuator's answer changed and no script has been run against it.

---

## Session 16, continued — 2026-08-28 — assignment, notes, feedback, and the queue

Two commits on `feat/alert-assignment-and-notes`, opened as
[#51](https://github.com/la3679/sentinelflow/pull/51) after
[#50](https://github.com/la3679/sentinelflow/pull/50) merged with all ten checks green.

### Three decisions that took longer to settle than to write

**A note takes no version and publishes no event.** The version was the interesting half: every
other operation replaces something, and a note is appended, so two analysts writing one at the same
time both succeed and both are kept. Demanding `expectedVersion` would refuse the second for no
reason a user could act on. Not publishing followed from the same look at the payload —
`alert-updated.v1.json` requires the version _after_ the change and has no field for the text, so a
note would either repeat the previous event's version or announce that something exists without
saying what. The stronger argument is the one about audience: an analyst's own words belong on a
detail page somebody opened, not on a topic that leaves the service, which is exactly the rule
`AlertRaiser` already follows when it builds a summary from a reason code.

**Feedback belongs to the assessment, not the alert.** Rescoring writes a new assessment rather than
editing one, so a label attached to the alert would silently follow a decision it was never given
about — and the value of these rows is that they label the features of one scored transaction.

**The queue's ordering is not a parameter.** Open before closed, then priority, then oldest first. A
`sort` parameter would let a console change which alerts an analyst sees first, and that is an
operational decision rather than a display one. Each of the three terms is asserted separately,
because each is a decision somebody could reasonably have made differently.

### One defect found by running it

An oversize page size answered 500. `@Validated` on a controller puts it behind a proxy and routes
parameter constraints through Hibernate Validator's `ConstraintViolationException`, while Spring
MVC's built-in method validation — which needs no annotation when a parameter carries a constraint —
raises `HandlerMethodValidationException`. Two mechanisms would mean two handlers answering the same
question, and which one fired would depend on an annotation nobody would think to look at. The
annotation went; the exception maps to the same 422 a body validation failure produces.

### Tests and results — every figure from a run on 2026-08-28

172 unit tests, 238 integration tests, coverage 89.8% lines and 78.6% branches, every gate met.
Contracts, links and formatting pass. Nothing run against the compose stack.

### Blockers

None.

### Next actions

Recorded in `PROJECT_STATE.md`: merge #51, then the reporting endpoints and the formula-injection-safe
CSV export, then close Phase 5 against its gate with the evidence for each criterion.
