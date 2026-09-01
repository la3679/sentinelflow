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

---

## Session 16, closed — 2026-08-28 — an emergency checkpoint on the reporting branch

**Stopped at 90% of the five-hour usage window, on the user's instruction**, part-way through
Phase 5's last deliverable. `CLAUDE.md`'s checkpoint policy applies at that point: no further
implementation, commit what is safe, push, record state.

### What the session landed

Three pull requests merged, each with all ten checks green:
[#49](https://github.com/la3679/sentinelflow/pull/49) alert creation and ADR-0011 §4,
[#50](https://github.com/la3679/sentinelflow/pull/50) the investigation state machine and ADR-0012's
authentication, and [#51](https://github.com/la3679/sentinelflow/pull/51) assignment, notes,
feedback and the queue reads. Phase 5 is one deliverable from its gate.

### What is on `feat/alert-reporting`, and what is wrong with it

The alert summary, the CSV export, and `CsvWriter` — which is the part that matters and is green,
with 17 unit tests covering every character a spreadsheet treats as a formula and the apostrophe
prefix going inside the quoting rather than outside it.

**Four of `AlertReportIT`'s ten cases fail, and the fault is in the fixture rather than the
endpoints.** The window is a class constant, so every test in the class writes into the same hour and
the four that assert an exact total count rows another test left behind. The two that assert no total
— the zero-key case and the formula-escaping case — pass, which is what says the production code
behaves.

**The failing suite is committed rather than deleted or disabled.** A test removed to go green is
worse than one that is red for a reason somebody wrote down, and the commit message carries the
diagnosis. The branch has no pull request, deliberately.

### Blockers

None. The fix is a window per test rather than per class.

### Next actions

Recorded in `PROJECT_STATE.md`: fix the fixture, document the two reporting paths in the OpenAPI
contract, run the full suite, open the pull request, then close Phase 5 against its gate with the
evidence for each criterion.

---

## Session 17 — 2026-08-28 — the reporting endpoints made green and put under contract

Resumed from Session 16's emergency checkpoint. `feat/alert-reporting` was pushed with four red
tests and no pull request; this session closed all three of the actions that state left.

### The four red tests were the fixture, and the previous session's diagnosis held

`AlertReportIT` derived its window from a class constant, so every test in the class wrote alerts
into the same hour and the four that assert an exact total counted whatever the tests before them had
left behind. Each test now draws its own hour from a per-run epoch through an `AtomicInteger` in
`@BeforeEach`. **No production code changed** — which is what the two tests that assert no total,
green throughout, had already predicted.

**The windows are two hours apart rather than adjacent, and that is not arbitrary.**
`theWindowIsHalfOpen` deliberately reads the window immediately after its own, to prove a row sitting
on the boundary is counted once rather than twice or never. With a one-hour stride that read would
have landed in the next test's window — and the fix for a fixture that leaks between tests would
itself have leaked between tests.

### The two reports are now in the contract they should have been written against

`contracts/openapi/sentinelflow-api.yaml` gained both paths, the shared `from`/`to` window
parameters, the `AlertSummary` schema, and `ExportTooLargeProblem` for the `413` the export answers.
The branch had built the endpoints without them, which left the authoritative contract saying the
reports did not exist.

Recorded rather than asserted: the window is half-open and both ends are required, the summary is
unpaged because its size does not depend on the data, the export is capped rather than paged, and
every cell is neutralised against formula injection.

### Tests and results

Every suite, at `68e019f`, under `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1`:

api **189 unit** and **250 integration** passed, 0 failures · JaCoCo gate met (line 0.8972,
branch 0.7945, instruction 0.9080) · web Vitest **24/24** · scoring pytest **169 passed** ·
`ruff check` clean · `mypy` no issues in 42 files · `spotless:check` 221 files clean ·
`eslint` 0 errors, 23 pre-existing warnings · `check-contracts.mjs` all passed ·
`prettier --check` clean · `check-docs.mjs` 160 links, no placeholders.

`ReportController` and `CsvWriter` are at 1.0000 instruction coverage, `AlertReportService` at
0.9589. The uncovered part is the export cap's refusal branch, which needs 10,001 alerts in one
window to reach. Left uncovered and said so, rather than lowered so a test could reach it: a cap the
test moves is not the cap that ships.

### Things worth keeping

- **The default `JAVA_HOME` on this machine still points at JDK 17**, and Maven's failure for it is
  `release version 25 not supported` from the compiler plugin rather than anything naming a JDK.
  Already recorded in ADR-0003 and in `PROJECT_STATE.md`'s known issues; recorded again here because
  it costs a build every session that forgets it.
- **The build's own `Results:` line and the JUnit XML files disagree**, and the console is the one
  to quote. Summing the XML gives 203 unit tests where the console says 189, because a `@Nested`
  class's cases appear in the container's file as well as its own. This was caught by comparing the
  local figure against the runner's: CI printed 189, which is also what
  `mvnw verify -DskipITs` prints locally. A number nobody else can reproduce by running the command
  is not evidence, whatever it was derived from.

### CI found a defect two green local runs could not

**#52's first run failed one test, and it was not a reporting test.**
`TransactionIngestionIT.retryReturnsTheOriginalResult` answered 500 rather than 202, on a duplicate
key for `TXN-000005`. The same commit was green locally, twice.

**Two allocators owned one namespace.** `SchemaFixtures` built `TXN-` and `ALT-` from an in-JVM
`AtomicInteger` starting at 1, while the application read `transaction_reference_seq` and
`alert_reference_seq`, which also start at 1. One container serves the whole fork, so the two met as
soon as the application had ingested as many transactions as the fixtures had written — a point that
depends on the order the suites happen to run in, and therefore on the machine. The class comment
asserted the counter "starts high enough that a value never collides"; it started at 1.

**Fixed by deleting the second allocator, not by moving it out of the way.** Offsetting the counter
would have made the collision unlikely, and unlikely is exactly what it already was. The fixtures now
draw both references from the same sequences the application reads, which makes it impossible.

**The regression test asserts the strong property.** `ReferenceAllocationIT` draws alternately from
the fixture and from the application and asserts every reference is exactly one more than the one
before it — only a single shared sequence produces that. Distinctness alone would pass with two
counters standing far apart, which is the state the old code was usually in. Confirmed it can fail by
reinstating the defect: `expected: 2L` on the first pair.

**What it cost, and what it is worth:** one CI round trip, and the lesson that a green local suite is
evidence about one interleaving of the tests. This is the second time in this project a defect has
been invisible until it ran somewhere else; the first was the three the compose stack found in
Phase 4.

### Blockers

None.

### Phase 5 closed against its gate

[#52](https://github.com/la3679/sentinelflow/pull/52) merged with all ten checks green, which put
Phase 5's last deliverable on `main`. The gate section in `PROJECT_STATE.md` records the evidence for
each of the four criteria rather than asserting them, in the shape the Phase 4 gate uses.

**Closing it found a real gap.** "Auditor mutation attempts fail as expected" had three tests, not
four: `POST /alerts/{id}/notes` carried a `@PreAuthorize` that nothing exercised. An annotation nobody
tests is a claim, so the criterion could not honestly be marked pass — the refusal now has a test of
its own, and a note is the quietest thing an auditor could add and the easiest guard to drop by
accident.

**Two deviations from the phase's deliverable list are stated rather than glossed.** Neither reporting
endpoint is paged: the summary is a fixed size whatever the window holds, and the export is capped and
refuses a wider window with 413, because a report somebody opens in a spreadsheet is a file rather
than a cursor. Both satisfy what the pagination requirement exists to enforce. And
`POST /api/v1/transactions` is still unauthenticated (ADR-0012 §5), which a phase whose gate includes
role authorization should say plainly.

**One gap in the product, recorded rather than hidden.** `alert_actions` reserves
`PRIORITY_CHANGED` and nothing writes it, because no endpoint changes an alert's priority. V4 is
merged and immutable, so the constraint keeps a value the application cannot write; removing the enum
constant would put the enum and the constraint out of step for somebody to rediscover later.

### `make smoke` run at last, and the defect it found

**23 passed and 0 failed on both paths** — `scripts/smoke/smoke.sh` and `scripts/dev/sf.ps1 smoke`,
neither of which had been executed since they were updated to expect 401 from the actuator's closed
endpoints. That item had been standing across three sessions.

**The first run failed two checks and the script was right both times.** `/actuator/env` and
`/actuator/beans` answered 404 rather than 401 because the running api image was 19 hours old and
predated ADR-0012's authentication. A rebuild made both green. The failure looked exactly like a wrong
expectation and was a stale artefact: a smoke test asks the _running_ stack, and the running stack is
only as current as the last build.

**Then no operator could log in — on a stack reporting 23 of 23 green.** `user_credentials` was
empty. `alreadySeeded()` asks whether any customer exists and takes that as meaning everything the
loader writes exists; `user_credentials` arrived with V10, after every existing database had been
seeded, so those databases have four operators, no passwords, and a seed that will never repair them
because they have customers.

The symptom is silent by design, which is what makes it bad: ADR-0012 §3 requires a refusal that never
says why, so the console simply stops working and the only documented route out is `make reset-demo`,
which destroys the data.

Fixed by repairing rather than resetting — a missing operator, role and credential are created even on
the skipped path, logged at WARN, and an existing credential is **never** rotated. Verified on the
live stack: the WARN fired naming four operators and `analyst.one` then logged in.

**Both reporting endpoints were then exercised over HTTP against the live stack** — 200 with the
attachment disposition and header row, 422 on an inverted window, 401 anonymous, and every enum key
present in the summary including the zeroes.

**The generalisable part, and it is the same shape as the morning's reference collision:** an
idempotency guard that tests a proxy for its work rather than the work itself is correct only until
the work changes. Both defects were checks that were true when written and silently stopped being
true.

### Phase 6 opened by auditing the console against the contract, rather than by writing a client

`AGENTS.md` says the mock-to-real migration is "limited to replacing `mockBaseQuery` with
`fetchBaseQuery`". Checking that endpoint by endpoint before writing anything found it is not: of the
console's eleven endpoints, two reach a real endpoint at the same verb and path and still need every
field renamed, four have no server counterpart at all, and five server endpoints have no client.

**The finding that mattered is a gate failure rather than a mapping detail.** `ALLOWED_TRANSITIONS`
in `domain/types.ts` is a second copy of the alert state machine and it disagrees with
`AlertTransitions.java` in both directions — the console offers two moves the server answers `409` to
and hides four that are legal. Phase 6's gate is "no dead controls", and a button that always fails
is one.

**The fix is to delete the copy, not correct it**, because a corrected copy is still a copy and the
next change to the state machine puts it out of step silently. That needed the server to publish the
answer on the happy path, which is what `legalTargets` now does — and deliberately as a property of
the alert _and the reader_, so an analyst is not offered the administrative close and an auditor is
offered nothing. Answering "legal from this status" would have moved the rule into the client rather
than removing it.

`AlertTransitions.namesOf` produces both that field and the `legalTargets` on the `409`, so what a
client was offered and what a refusal names cannot disagree. There is a test that asserts it.

### Tests and results

`mvnw -B verify` at `9580a96` → **195 unit and 258 integration tests**, 0 failures; JaCoCo gate met
at line 0.8969, branch 0.7962 · `check-contracts.mjs` passed · `prettier --check` clean ·
`check-docs.mjs` 160 links, no placeholders. All ten required checks green on #56 and #57.

### Things worth keeping

- **Auditing first was worth a full session's caution.** Every one of the four pieces the migration
  actually needs would have been discovered halfway through writing a typed client against the wrong
  shape, and two of them (the actor field, the missing `expectedVersion`) are the kind that produce a
  working screen with a broken audit trail.
- **Three console-side things are decisions rather than work**: what resolves an `assigneeId` to a
  name, whether the API grows an overview aggregate or the console composes one, and what a
  system-health screen may show. Each is recorded in the audit with what exists on both sides.

### Next actions

Recorded in `PROJECT_STATE.md`: the transport and the real authentication flow, then the types and
the deletion of `ALLOWED_TRANSITIONS`, then a decision per invented endpoint starting with the
overview. Separately and blocking nothing, `make reset-demo` then `make seed` is the user's to run —
the demo database predates alert creation and holds zero alerts.

---

## Session 18 — 2026-08-28 — the console starts calling the API, and signing in to do it

| Field           | Value                                                                                 |
| --------------- | ------------------------------------------------------------------------------------- |
| Start / end UTC | 2026-08-28T15:40Z / 2026-08-28T16:35Z                                                 |
| Starting SHA    | `1efa702` on `main`                                                                   |
| Ending SHA      | `360167b` on `feat/web-transport-and-auth`                                            |
| Objective       | Phase 6, piece 1 of the API migration: the transport and the real authentication flow |

Resumed from `PROJECT_STATE.md`'s first next action. Nothing was redone: the audit and the alert's
legal targets were already merged, `main` was clean and level with the remote, and all five checks
on `1efa702` were green.

### The migration's first piece, and the two questions it turned out to depend on

The audit had described piece 1 as "`fetchBaseQuery`, the login flow, the bearer header, `401`
handling". Two of its prerequisites were not in that list and neither is a detail.

**The API had no CORS configuration at all.** Not a weak one — none. The console had never made a
request, so nothing had ever had to answer how a browser is permitted to make one across the origin
boundary ADR-0002 created and ADR-0012 §1 leans on when it rejects cookie sessions.
[ADR-0013](../adr/0013-console-to-api-cross-origin-access.md) decides it: an explicit allow-list
from `SENTINELFLOW_CORS_ALLOWED_ORIGINS`, registered for `/api/v1/**` only, no wildcard, no
credentials, preflights cached for an hour.

**Proxying `/api/v1` through the console's nginx was the tempting alternative and was rejected.** It
needs no CORS at all, which is exactly why it is worth naming: it makes the two services one origin
as far as any browser is concerned, quietly removing the premise ADR-0012 rests on, and it puts API
routing in a container whose job is serving static files.

**`TokenResponse` grew `roles`.** The audit said "roles read from the token"; the contract's own
`expiresAt` description says a client that parsed the token would be reading a structure the service
is free to change. Both cannot be right. Sending the roles beside the token — from the same
`TokenIssuer.issue` call that puts them in the claim — is the form that satisfies each. A test
asserts the response and the claim are equal, because if they could disagree an operator would be
offered a control under one capacity and audited under another.

### Three defects, each invisible until the console made a request

- **The Vite dev server defaulted to port 8080**, which is the port `compose.yaml` publishes the API
  on. Harmless for as long as the console called nothing; the moment it does, `make up && bun run
dev` is two servers fighting over one port, and Vite quietly takes another — changing the origin
  the API was told to expect. Pinned to 5174, `strictPort`.
- **`API_BASE_URL` defaulted to the relative `/api/v1`.** Against the console's own nginx that
  resolves to a path it does not serve, so `try_files` answers with the SPA shell and a `200` the
  client then tries to parse as JSON. A 404 would have been the kinder failure. It is absolute now,
  and a build argument, because Vite inlines `VITE_*` rather than reading it at runtime.
- **The `/forbidden` screen told operators to "switch the simulated role in the header".** That
  control is exactly what this change removes. Phase 6's gate is "no dead controls" and dead copy is
  the same failure one layer up.

### Four e2e tests had been passing by accident of a full page load

The suite now stubs the API at the network boundary — CI has no backend, and stubbing there is what
exercises the real transport, the real bearer header and the real `401` rather than a fixture
pretending to be them.

Four tests then failed, and every one of them was the test rather than the console. Two keyboard
tests assumed the tab order started at the top of the document, which is only true after a fresh
load; signing in ends with a click. Two timing tests read the DOM in the gap between the URL
changing and React committing the new tree, so they were driving the sign-in screen while believing
they were somewhere else. `signIn` now waits for the signed-in shell, and the keyboard tests clear
focus rather than reloading — a reload would sign the operator out, which is the design.

### The redirect sent operators back where they came from

`RequireSession` read `location.pathname` inside its effect, so after redirecting to `/login` the
effect re-ran against the new location and redirected again with `next=/login`. The intended path is
captured once, on the way in. Two navigations racing on sign-out had the same shape and the same
fix: the button drops the session and lets the gate do the navigating.

### Tests and results

| Check                                              | Result                                                                        |
| -------------------------------------------------- | ----------------------------------------------------------------------------- |
| `bun run verify` (apps/web)                        | **PASS** — lint clean, typecheck clean, 38 unit tests in 6 files, build built |
| `bun run test:e2e` (apps/web)                      | **PASS** — 68 tests, desktop and tablet, axe clean on all eight routes        |
| `./mvnw verify -Dit.test=OperatorAuthenticationIT` | **PASS** — 12 integration tests, JDK 25.0.4.1+1                               |
| `./mvnw test -Dtest=CorsPropertiesTests`           | **PASS** — 9 unit tests                                                       |

`apps/api`'s full suite was **not** re-run: only the two suites this change touches were. The last
full figure remains the one recorded against `9580a96` above. CI on
[#59](https://github.com/la3679/sentinelflow/pull/59) is what will run everything.

### Things worth keeping

- **A migration's first piece is where its unstated prerequisites surface.** Neither CORS nor the
  roles-in-the-response question appears in an audit that was thorough about endpoints, because both
  are properties of the connection rather than of any endpoint.
- **Three of this session's defects were latent for weeks and all three needed one thing to appear:
  a real request.** A console that has never called anything cannot have a wrong base URL, a port
  conflict, or a missing CORS rule — it can only have all three waiting.
- **`transport: "mock"` per endpoint rather than a global flag.** The console is genuinely
  half-migrated and will be for two more pieces of work. One switch would have to claim it is
  entirely one thing or the other, and whichever it claimed would be a lie about four screens.

### Next actions

Recorded in `PROJECT_STATE.md`: merge #59 once green, then the types and the deletion of
`ALLOWED_TRANSITIONS` — where the `409` is the real work rather than the renames — then a decision
per invented endpoint starting with the overview. Separately and blocking nothing, `make reset-demo`
then `make seed` is still the user's to run.

## 2026-08-28 — the README reconciled, and the console wired to the API

### What was done

**The README was reconciled against the repository first**, at the user's request. It still said
Phase 5 was in progress and named the reporting endpoints and the CSV export as what does not run
yet; both merged in [#52](https://github.com/la3679/sentinelflow/pull/52). It also carried a known
limitation saying the console's sign-in screen is presentational, which
[#59](https://github.com/la3679/sentinelflow/pull/59) had made false. Every replacement was checked
against the code — the handlers, the contract paths, `transport.ts` and `sentinelApi.ts` — rather
than against the state file alone. Merged as [#61](https://github.com/la3679/sentinelflow/pull/61).

**Then Phase 6's second piece**, which turned into two pull requests because of what it found:
[#62](https://github.com/la3679/sentinelflow/pull/62) built three transaction read endpoints that
did not exist, and [#63](https://github.com/la3679/sentinelflow/pull/63) rewrote the console's
domain against the contract and pointed every alert and transaction screen at the API.

### Things worth keeping

- **An audit that reads one of two documents and reports on both has not been made.**
  `API_MIGRATION_AUDIT.md` says it checked the contract _and the handlers_; on
  `GET /transactions`, `GET /transactions/{id}` and `GET /transactions/{id}/assessment` it checked
  only the contract, and recorded as "maps, with field renames" three endpoints that answered 404.
  This is the third instance of one shape this project keeps meeting — the reference collision, the
  seed's idempotency guard, and now this — where a check tests a proxy for the thing rather than the
  thing. **It was found by writing the client the audit called for**, which is the only way it was
  ever going to be found.
- **A fourth endpoint is still missing and it is a decision, not a gap.** `GET /models/active` is in
  the contract at Phase 4 with no handler, and `model_registry` has never had a row written to it —
  the registry of record is the one `apps/scoring` serves from disk. Building the endpoint over an
  empty table would produce a permanent 404 that looked like an outage.
- **Deleting a control is a legitimate outcome of a migration.** Three went in #63 — the queue's
  search box and risk-band filter, the feed's authorisation-status filter, and the assignee picker —
  because the API has no counterpart for any of them, and a filter that quietly matches everything
  is exactly the dead control Phase 6's gate forbids.
- **The assignee case is worse than the audit recorded, and the sharper version is the useful one.**
  "An assignee renders as a UUID" is a display problem. "This console cannot assign an alert to
  anybody" is the real position: assignment takes an identifier, nothing resolves a name to one, and
  the login response carries roles but not the operator's own identifier — so not even "assign to
  me" is buildable. Release is the only assignment it can honestly make.
- **PostgreSQL cannot type a bare `:param IS NULL`.** The paged transaction query is valid HQL and
  valid JPQL and was refused at prepare time with `could not determine data type of parameter $5`.
  The enum and string filters beside it need no cast, so the failure looks arbitrary until you see
  that a placeholder in that position has no context to be typed from. Two `cast(... as Instant)`
  calls fix it, and the javadoc says they are load-bearing — no test of the query's _logic_ would
  catch their removal.
- **A stub in the contract's shapes is what makes an e2e suite with no backend mean anything.** The
  Playwright stub answers field for field with `contracts/openapi/`, so a screen that passes there
  cannot break against the real API for a reason the suite could have caught. Two of the new tests
  are the ones that would have caught this session's own mistakes.

### Verification

| Check                                | Result                                                              |
| ------------------------------------ | ------------------------------------------------------------------- |
| `./mvnw verify` (apps/api)           | **PASS** — 204 unit and 279 integration tests, 0 failures           |
| JaCoCo gate (LINE 0.80, BRANCH 0.70) | **met** — line 0.8991, branch 0.8030                                |
| `bun run verify` (apps/web)          | **PASS** — 41 unit tests in 6 files                                 |
| `bun run test:e2e` (apps/web)        | **PASS** — 72 tests, axe clean on all eight routes                  |
| contracts, docs, formatting          | **PASS** — 182 links, 0 broken; all contract checks; prettier clean |

All ten required checks passed on each of #61, #62 and #63.

### Next actions

Recorded in `PROJECT_STATE.md`: piece 4 of the migration — a decision per invented endpoint, with
the overview and the model registry being the two that are genuinely architectural rather than
mechanical. Separately and blocking nothing, `make reset-demo` then `make seed` is still the user's
to run.

## 2026-08-28 — the console's last four screens, and what deciding them cost

### What was done

[ADR-0014](../adr/0014-where-the-console-s-remaining-screens-get-their-data.md) decides where the
four screens the console invented get their data, and #65 builds all four. The migration audit is
closed and `apps/web/src/mocks/` is deleted.

Two API endpoints came with it: `GET /models/active`, which the contract had at Phase 4 with no
handler, and `GET /system/health`, which is new.

### Things worth keeping

- **Four screens, four different answers.** The temptation was one rule — "add an endpoint" or
  "compose in the client" — and it would have been wrong three times out of four. The model screen
  needed an API composition because only the scoring service knows what artifact is loaded. The
  overview needed a client composition because an aggregate would have been a second implementation
  of risk-band counting. Health needed a small new endpoint. Reports needed nothing built at all: the
  endpoints already existed, and one of them had been tested and unused since Phase 5.
- **A contract entry is not an implementation, and this is the second time that has bitten.**
  `GET /models/active` had been documented since Phase 4 and never built, exactly like the three
  transaction reads found earlier the same day. The pattern to take away is that **a document
  describing what an endpoint answers is evidence about intent, not about behaviour** — and the only
  thing that distinguishes them is a caller.
- **An empty table is a decision waiting, not a gap to fill.** `model_registry` has constraints,
  constraint tests and no rows. Building the model endpoint over it would have produced a permanent
  404 that looked like an outage. It stays empty and recorded as debt, because populating it means
  deciding the API is the registry of record — with promotion, audit and rollback — which is much
  more than a read screen should drag in.
- **Deleting three panels of invented numbers was the largest single improvement in the console.**
  Throughput per hour, latency percentiles, consumer lag and dead-letter depth were charts that had
  never been connected to anything that measured them. The screens are smaller and say which phase
  brings each back. A figure nobody measured is worse than no figure, because somebody quotes it.
- **A metadata read must not be able to move the pipeline's circuit breaker.** `ScoringClient.modelInfo`
  neither consults the breaker nor reports to it: a dashboard somebody refreshes could otherwise
  open it and degrade every assessment, and a successful metadata read says nothing about whether
  inference is answering. One read, one timeout, no retry — and a test asserts eight consecutive
  failures leave the breaker closed.
- **A composition with two owners needs a field saying which half is missing.** `modelAvailable` is
  explicit rather than inferred from a null `modelVersion`, for the same reason `degraded` is
  explicit on an assessment: a reader working it out for themselves is a rule in every client, and
  the two disagree the day a legitimate null appears.
- **The unit-test count fell from 41 to 37, and that is the deletion.** The suite that asserted the
  fixture layer's determinism went with the fixture layer. The one transport test that asserted the
  overview made _no_ network request was inverted rather than deleted, so a reader sees the change
  rather than a disappearance.

### Verification

| Check                       | Result                                                    |
| --------------------------- | --------------------------------------------------------- |
| `./mvnw verify` (apps/api)  | **PASS** — 209 unit and 289 integration tests, 0 failures |
| JaCoCo gate                 | **met** — line 0.8953, branch 0.7931                      |
| `bun run verify` (apps/web) | **PASS** — 37 unit tests in 5 files                       |
| `bun run test:e2e`          | **PASS** — 82 tests, axe clean on all eight routes        |
| contracts · docs · format   | **PASS** — 184 links, 0 broken                            |

### Next actions

Recorded in `PROJECT_STATE.md`: ADR-0015 on SSE versus WebSockets, which the plan lists as a Phase 6
deliverable and which should be written against what the console now does rather than what it was
going to; then exercising the console against the running stack, because the API's new call to the
scoring service's `/v1/model` has never been made against the real service; then closing Phase 6
against its gate.

---

## 2026-08-29 — Phase 6 closed: ADR-0015, the live stack, and the row that broke every alert

| Field           | Value                                                                                                                               |
| --------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| Start / end UTC | 2026-08-28T23:55Z / 2026-08-29T01:20Z                                                                                               |
| Starting SHA    | `c0d4201` on `main`                                                                                                                 |
| Ending SHA      | recorded by the merge of the branch this session opened; `main` was green at every push                                             |
| Objective       | Continue from "Next three actions": decide ADR-0015, exercise the console against the running stack, close Phase 6 against its gate |

### Work completed

**ADR-0015, merged as [#67](https://github.com/la3679/sentinelflow/pull/67).** The build prompt's
§8.6 picks SSE over WebSockets and does not say whether to stream at all; both halves are decided,
against what the console does rather than what it was going to. Phase 6 ships bounded polling
because nothing produces transactions continuously; when a stream is built it is SSE read with
`fetch`, and three preconditions gate building it.

**Two console defects the ADR found while establishing its own facts.** The alert queue had no
refresh at all, and `setupListeners` had been wired since the store was written with nothing opted
into it. Both fixed, with tests that were checked against an inverted implementation.

**The console exercised against the running stack**, which is the item that has repeatedly been
worth more than any suite. `GET /models/active` reached the real scoring service for the first time.
`GET /system/health` answered. Six posted transactions were scored, banded, and raised alerts.

**One defect no suite could have found.** Every `transaction.created` event was being dead-lettered
after five attempts because the `system` principal was missing from the local database — deleted by
an earlier session's `TRUNCATE users`, following an instruction in `PROJECT_STATE.md` itself. The
API now refuses to start without it, `MigrationIT` asserts the migrations write it, Runbook 1
records the generalisation, and the instruction that caused it is corrected.

**Phase 6 closed against its gate**, with the evidence per criterion and three deviations stated.

### Things worth keeping

- **An ADR about live updates written before the console talked to the API would have been about an
  imagined system.** Every fact ADR-0015 rests on — one screen polls, the queue does not refresh,
  nothing produces continuously, the token is a header with a 30-minute expiry — is a property of
  what was built, and three of them were surprises to the session that wrote it.
- **The most expensive shape a failure can take is a per-message error inside a consumer.** Five
  retries deep, on records that would fail identically for ever, with every health signal green.
  Checking the same condition once at startup turns an afternoon of consumer logs into a refusal to
  start with the repair in the message.
- **A `RETRY_EXHAUSTED` class does not mean the failure is transient.** It means a handler threw
  something that was not marked non-retryable. When every record fails with the same exception type,
  the class is wrong and the message is right.
- **A state file can carry a landmine.** The truncate instruction was written by a session solving a
  real problem and it destroyed reference data the seed does not write back. What made it dangerous
  was that it named `users` in a list of tables that otherwise held only generated data.
- **`setupListeners` guards itself with a module-level flag**, so a second call registers nothing.
  A test that armed its own store would have proved nothing about a real focus event, and would have
  passed anyway — which is why the two refetch tests exercise the application's own store and were
  checked against an inverted implementation before being believed.
- **A coverage threshold can be honest and nearly vacuous at the same time**, and it is better to
  say so in the config than to let 25% be read as a claim. What gates this console is Playwright.

### Verification

| Check                              | Result                                                    |
| ---------------------------------- | --------------------------------------------------------- |
| `./mvnw verify` (apps/api, JDK 25) | **PASS** — 226 unit and 290 integration tests, 0 failures |
| JaCoCo gate                        | **met** — line 0.8955, branch 0.7931                      |
| `bun run test` (apps/web)          | **PASS** — 41 tests in 5 files                            |
| `bun run test:e2e`                 | **PASS** — 82 tests, axe clean on eight routes            |
| `format:check` · `check-docs.mjs`  | **PASS** — 204 links across 46 files, 0 broken            |
| CI on #67                          | **PASS** — all ten required checks                        |

### Where this session stopped, and why

**At the Phase 6 boundary, on the user's instruction**, with weekly usage at 95%. Phase 7 was
surveyed and not started: no branch, no commit, no dependency added. What the survey established is
recorded below so the next session does not repeat it.

- **The metric baseline is larger than "not started" suggests.** Twenty-six `sentinelflow.*` meters
  already exist across the outbox, the consumer, the scoring client, risk assessment, alerts and
  login. What §18.2 of the build prompt asks for and this does not have: transaction ingestion
  result and latency, Kafka consumer lag, and database pool figures. SSE connection count is
  not-applicable by [ADR-0015](../adr/0015-live-updates-polling-and-server-sent-events.md).
- **The envelope already has a `traceId` field and nothing writes to it.** `event-envelope.v1.json`
  requires it, `OutboxEvent` and `AuditLogEntry` carry the column, and every event on the local
  stack has `"traceId": null`. Trace propagation has a landing place waiting for it.
- **Correlation already flows end to end**: `CorrelationIdFilter` validates or generates a UUIDv7,
  puts it in the MDC, echoes it in a response header and writes it onto the outbox row.
- **Spring Boot 4.1.1 ships structured logging** (ECS, GELF, Logstash, or a custom
  `StructuredLogFormatter`) with no extra dependency, so §18.3's JSON logs need no new library.
  Micrometer Tracing 1.7.1 and OpenTelemetry 1.62.0 are managed by the Boot BOM, so the tracing
  bridge needs no version pin either — but adding it is a dependency decision that belongs in the
  observability ADR rather than ahead of it.

### Next actions

Recorded in `PROJECT_STATE.md`: Phase 7. The metric set first, because three deleted console panels
and ADR-0015's third precondition are both waiting on it; then structured logging with redaction and
trace propagation across the Kafka hop; then the dashboards and the failure drills that make the
four existing runbooks stop being aspirational.

---

## 2026-08-30 — Phase 7: four deliverables, and eight defects only the running stack could show

| Field           | Value                                                                                                                  |
| --------------- | ---------------------------------------------------------------------------------------------------------------------- |
| Start / end UTC | 2026-08-30T21:55Z / 2026-08-31T00:20Z                                                                                  |
| Starting SHA    | `ccbe2a8` on `main`                                                                                                    |
| Ending SHA      | `fa015ab` on `main`                                                                                                    |
| Objective       | Begin Phase 7: the observability ADR, the metric set, structured logging with redaction, trace propagation, dashboards |

### Work completed

Four of Phase 7's six deliverables, merged as PRs
[#70](https://github.com/la3679/sentinelflow/pull/70) through
[#73](https://github.com/la3679/sentinelflow/pull/73), each with all ten required checks green.
`PROJECT_STATE.md` carries the detail; this records what the session learned rather than repeating
it.

**[ADR-0016](../adr/0016-observability-signals-and-their-boundaries.md)** fixes what each signal
answers before any of it was built: metrics say whether the system is healthy and may carry only
closed enumerations, traces say where one transaction went, logs say what happened in words. Its §4
was rewritten once during the session, before any code depended on it — the first draft's redaction
list would have outlawed logging an account reference, which is the field an operator uses to find
the thing they were paged about. The split that survived is what an investigation needs against what
a disclosure would cost.

### The pattern this phase exists for, repeated eight times

Every one of these was invisible to a green suite. The phase was named for that failure mode before
any of them were found, which is the only satisfying thing about the list.

1. **`micrometer-tracing-bridge-otel` alone produced no `Tracer` bean.** Boot 4 moved the
   autoconfiguration into its own module, exactly as it did with Flyway in Phase 2. Three tests were
   green while tracing did nothing at all.
2. **`management.otlp.tracing.*` binds nothing in Boot 4.1.** It is the pre-4.1 spelling, still
   present in the configuration metadata with no description and no default. Propagation worked
   perfectly throughout, so every test passed while no span ever left the process — found by querying
   Tempo and getting a 404, then finding `tempo_distributor_spans_received_total` absent entirely.
3. **The OpenTelemetry starter auto-configured a second metrics registry** pointed at
   `localhost:4318`, failing with a stack trace on a timer inside the container.
4. **Tempo and the collector cannot be healthchecked**: both images are distroless, and the probe
   failed with `stat /bin/sh: no such file or directory` while Tempo ran perfectly — which then
   blocked the collector that depended on it being healthy.
5. **Five metric series did not exist until something rare happened.** Alert counters, ingestion
   conflicts and consumer outcomes. An absent series and a zero look identical on a graph and
   completely different in an alert rule, so a rule reading "conflicts above zero" never fires on the
   service it was written for.
6. **The outbox publication timer had no buckets**, so ADR-0016 §3's "Prometheus computes the
   percentiles" was impossible for it and the panel returned nothing.
7. **A dashboard query of this session's own** named `CONFIRMED_FRAUD` and `FALSE_POSITIVE`, which
   are not statuses this system has.
8. **A pre-existing race in `TransactionCreatedConsumerIT`** that CI failed and two local runs
   passed: the test handler increments its counter on entry, so waiting on it and then reading the
   ledger races the commit. Enabling listener observation widened the window enough to make a latent
   flake a red build.

Two more were found by tests behaving correctly, and both are recorded in the code rather than worked
around: Spring's own `RestClient` logs the body it is about to send at `DEBUG`, so the first
`LogRedactionIT` was catching its own harness; and `StreamHandler` binds its stream at construction
while pytest swaps its capture buffer between phases, so three redaction assertions passed against an
empty string — the worst possible way for a redaction test to be green.

### What was checked, and how

- **One trace end to end**, on the running stack: `c591f5172d73068c9f55902c1777d29d`, seven spans,
  root at the HTTP ingest with the Kafka consumer beneath it and the scoring call beneath that.
- **48 dashboard panel queries** run directly against Prometheus: 0 empty, 0 malformed, against an
  API restarted less than a minute earlier. Running the queries rather than looking at the dashboard
  is what turned "five panels are blank" into five fixed defects.
- 232 API unit tests, the full 303-test Testcontainers suite, 186 scoring tests, `ruff` and
  `mypy --strict` clean.

### Deliberately not done

- **No alert rules.** `rule_files: []` still, with its reason: a rule with no runbook is a pager
  nobody knows how to answer. They belong with the runbooks, which are the next action.
- **The scoring service emits no spans.** It reads `traceparent` and puts the ids on its log lines
  instead. Emitting spans means three runtime dependencies to add one server span inside a hop the
  caller already measures, and `apps/scoring/src/sentinelflow_scoring/trace.py` argues the boundary
  and says where the decision changes.
- **The Phase 7 gate is not claimed.** Two criteria met with evidence, one partly met, one not
  attempted. The gate table says which.

### One instruction recorded during the session

The user asked that the Phase 6 assignee-identity limitation be carried forward as required pre-v1
work rather than left as a note. `PROJECT_STATE.md` now has a "Required before v1 — carried forward"
section with a binding definition of done, the resume instructions point at it, and Phase 10's gate
in the implementation plan requires it. The same section records the two items no session may mark
complete on a person's behalf: the screen-reader pass and the manual authenticated browser
walkthrough.

### Next actions

Recorded in `PROJECT_STATE.md`: the resilience drills as tests rather than a document, then the nine
runbooks the drills give real content to, then close the Phase 7 gate honestly and start Phase 8.

---

## 2026-08-31 — Phase 7 closed: two drills, nine runbooks, thirteen rules, four log leaks

| Field           | Value                                                                                                        |
| --------------- | ------------------------------------------------------------------------------------------------------------ |
| Start / end UTC | 2026-08-31T14:45Z / 2026-08-31T16:10Z                                                                        |
| Starting SHA    | `a1099ba` on `main`                                                                                          |
| Ending SHA      | `77ad75c` on `main` (PR #83's merge)                                                                         |
| Objective       | Finish Phase 7: the resilience drills, the nine runbooks and their alert rules, then close the gate honestly |

### Work completed

**The two drills, as tests rather than as a document** (PR #82). `ScoringOutageDrillIT` drives thirty
transactions through the whole path while scoring refuses, and asserts the system-level consequence
ADR-0008 promises: every one assessed, every one degraded with no model score, none dead-lettered,
and the outage costing five records' worth of HTTP attempts rather than thirty. `BrokerOutageDrillIT`
freezes the broker mid-run and asserts every clause of ADR-0005's claim — ingestion still answering
`202`, the outbox holding with `last_error` recorded, the gauges reporting the backlog, nothing
reaching `FAILED`, and the backlog draining to exactly one ledger row and one assessment per event.

**Nine runbooks and thirteen alert rules** (PR #82). Five runbooks written, four revised against the
dashboards, a rewritten metric table covering all twenty-one application metrics plus the framework
ones, and a table saying which of the five dashboards answers which question.
`infra/prometheus/rules/sentinelflow.yml` replaced `rule_files: []`, each rule annotated with the
runbook section that answers it.

**The redaction claim widened until it matched the ADR** (PR #83). `LogRedactionIT` now runs at
`logging.level.root=DEBUG`, pins nothing itself, and drives five paths instead of one.

### Defects found, and where each came from

1. **`sentinelflow_consumer_deadletter_total` and `sentinelflow_consumer_undeliverable_total` had no
   series at all** on the running stack. Found by writing Runbook 1's alert rule. An absent series
   and a zero are identical on a graph and different in a rule, and the rarest outcome is the one an
   alert is written against. Six series now registered at zero in the constructor — the third place
   this decision has had to be applied.
2. **Hibernate dumps every entity in the persistence context at `DEBUG`**, carrying a transaction's
   amount, its device handle, its idempotency key, an outbox row's whole payload and an alert
   action's note. Found by raising the redaction test to the root logger. An entity cannot defend
   itself — the printer reads properties through the persister rather than calling `toString` — so
   `org.hibernate.orm.core` and `org.hibernate.orm.jdbc.bind` are now pinned in `application.yaml`
   beside `org.hibernate.orm.jdbc.error`, which was the precedent.
3. **`AlertNoteRequest` printed the analyst's note** through Spring's own `Read "application/json"
to […]` line at `DEBUG`. ADR-0016 §4 forbids a whole request body at every level. Fixed with the
   ADR's own first mechanism on the note, transition, assignment and feedback records.
4. **`TransactionResponse` was safe by accident**, because Spring's response-side log line truncates
   at 100 characters before reaching the amount. The position of a field in a generated `toString`
   is not a control; given a redacting one.

### Three corrections to documents, made rather than glossed

Reprocessing a dead-lettered event, reviving a `FAILED` outbox row and rescoring a degraded
assessment were each described in `docs/operations/RUNBOOKS.md` as "Phase 5 work". **Phase 5 shipped
and its deliverable list never contained any of them**, and nothing in the implementation plan
allocates them now. The runbooks now say so and give the manual procedure. Recorded here as the
discrepancy `CLAUDE.md` asks for: the repository was right and the documents were wrong.

### One finding recorded rather than fixed

**There is no index on `alerts (created_at)` alone**, so a report window is a sequential scan plus a
sort. In Runbook 8's diagnostics. Not fixed: a measured optimisation is Phase 9's, and an index added
with no before-and-after is a change nobody can justify afterwards.

### Tests and results

- `./mvnw -B verify` — 233 unit and 309 integration tests, 0 failures, JaCoCo gate met
- `uv run pytest` — 187 passed; `ruff` and `mypy --strict` clean over 46 source files
- `promtool check config` and `check rules` — valid, 13 rules
- All 13 rule expressions run against the live Prometheus; every metric they read exists
- Prometheus reloaded with the rules mounted — 13 loaded, 13 inactive, 0 evaluation errors
- 13/13 runbook anchors resolve; `check-docs.mjs` — 216 links across 47 files, 0 broken
- CI on #82 — all ten required checks, and both drills ran on the Ubuntu runner

### Deliberately not done

- **No Alertmanager.** Nothing pages anybody, and adding it would be a service with no recipient on
  a stack that runs on one laptop. Stated in the rules file's own header and in the gate table.
- **No calibrated thresholds.** Most are derived from a configured budget or interval and name it;
  two are conventions and say so. Phase 9 measures.
- **The seven open Dependabot pull requests were not triaged.** They are action 1 in
  `PROJECT_STATE.md` and two of them are majors.

### Next actions

Recorded in `PROJECT_STATE.md`: triage the seven Dependabot pull requests, then Phase 8's threat
model against the four holes this repository has already documented, then the scanning and
supply-chain half.

---

## 2026-08-31 (second half) — Phase 8 opened: dependency triage, and two things `docker port` found

| Field           | Value                                                                                               |
| --------------- | --------------------------------------------------------------------------------------------------- |
| Start / end UTC | 2026-08-31T16:10Z / 2026-08-31T18:45Z                                                               |
| Starting SHA    | `77ad75c` on `main`                                                                                 |
| Ending SHA      | recorded by PR #93's merge                                                                          |
| Objective       | Phase 8, action 1: triage the open Dependabot pull requests, and act on whatever the triage exposed |

### Work completed

**The dependency round, which grew while it was worked.** Seven pull requests became twelve: merging
the first five changed `bun.lock` and Dependabot opened five more. Six merged after local
verification, two were resolved by other means, four are open — the table in `PROJECT_STATE.md`
§"Dependabot" holds each one's position and the checks that were actually run against it.

**TypeScript 7.0 refused, 6.0 not** (#85). TypeScript 7 is fine here — `tsc --noEmit` clean, tests
and build pass — but `typescript-eslint` 8.65.0 refuses to load against it by name, so taking the
bump meant shipping without a linter. `.github/dependabot.yml` ignores the range `>=7.0.0 <7.1.0`
rather than the major, because 7.1 is the release typescript-eslint says it is working towards.

**Four React Hooks defects fixed** (#84), then the plugin bump taken by hand (#93) after Dependabot
declined to rebase #80 twice. Three of the four were in components nothing imported; the fourth was
`ChartFrame` setting state from an effect, replaced with `useSyncExternalStore`.

**Every published port now binds to loopback** (#91). See below — this is the finding of the session.

**37 unused shadcn components and 30 runtime dependencies deleted** (#92). 52 direct runtime
dependencies down to 22, 749 resolved packages down to 713, 3,041 lines out of the tree. Done before
Phase 8 adds CodeQL and an SBOM rather than after.

### The finding

**`docker port sentinelflow-postgres` reported `0.0.0.0:5432`.** Every service in `compose.yaml` was
published as `"HOST:CONTAINER"`, which Docker binds to all interfaces. On any shared network the
stack offered PostgreSQL with the password `make bootstrap` generates, Kafka, Grafana with the admin
password it generates, Prometheus, Tempo, the scoring service, the console, and an API whose
ingestion endpoint is deliberately unauthenticated until this phase gives it its own credential.

ADR-0012 §5 argues for leaving ingestion open partly on the grounds that "the demo stack binds to
localhost". **The argument was sound and its premise was false**, which is worse than either alone.
Fixed rather than reworded: every port binds to `${SENTINELFLOW_BIND_ADDRESS:-127.0.0.1}`, verified
`127.0.0.1` on all eight afterwards, and the ADR carries a dated correction so the change of factual
basis is auditable.

**The lesson generalises: a security claim in a document is worth exactly one command.** This one had
been true in nobody's environment since the compose file was written.

### Tests and results

- `bun run lint` · `typecheck` · `test` · `test:e2e` · `build` on every web branch verified — 0
  errors, 41 unit tests, 82 Playwright tests, build and prerender pass
- `uv run pytest` — 187 passed; `ruff` and `mypy --strict` clean over 46 source files
- `./mvnw -B verify -DskipITs` — 232 tests on #75's branch; `spotless:check` clean
- `make smoke` — 23 passed, 0 failed, after rebinding every port
- `docker port` on all eight containers — 8/8 `127.0.0.1`, from 8/8 `0.0.0.0`
- `bun install --frozen-lockfile` — 617 installs across 694 packages, from 650 across 749
- `bun run format:check` · `check-docs.mjs` — clean · 216 links across 47 files, 0 broken

### Deliberately not done

- **None of Phase 8's own seven deliverables.** The threat model, rate limits, an ingestion
  credential, CodeQL and the SBOM are all untouched. What landed is the triage and what the triage
  exposed.
- **#87 (`zod` 4) and #90 (`recharts` 3) are not verified.** Both are majors on live routes and both
  need the browser suite, not only unit tests.
- **#86 and #88 are verified and unmerged.** They passed every local check; the session stopped
  before they were merged, and that is recorded rather than glossed so nobody re-runs the work.

### Next actions

Recorded in `PROJECT_STATE.md`: finish the four open Dependabot pull requests, then the threat model
against the four holes this repository already documents, then CodeQL and the SBOM.

---

## 2026-08-31 (third session) — Phase 8 closed: the Dependabot round, the threat model, the ingestion credential, CodeQL and the SBOM

| Field           | Value                                                                                                           |
| --------------- | --------------------------------------------------------------------------------------------------------------- |
| Start / end UTC | 2026-08-31T21:14Z / 2026-08-31T23:15Z                                                                           |
| Starting SHA    | `d1911da` on `main`                                                                                             |
| Ending SHA      | `a3e4534` on `main`, plus this checkpoint                                                                       |
| Objective       | Finish the open Dependabot round, then deliver Phase 8 — threat model, hardening, scanning, SBOM — and close it |

### Work completed

**The Dependabot round, finished.** Four pull requests were open and two of them had been recorded as
"verified locally". That verification was against the base they were opened on, which predated #92
deleting 30 runtime dependencies — so each was rebased onto the current `main`, re-verified, and
force-pushed to its own branch, because a push from a real account is what re-dispatches the checks
the lockfile job's `GITHUB_TOKEN` cannot start. #86, #87, #88 and #90 all merged.

**Two majors landed with tests that had never existed.** `zod` is live in the login and alert-detail
routes and no test reached its refusal path — the end-to-end sign-in fills well-formed values, so the
schema and its resolver were only ever exercised on the happy path. `recharts` is live on two screens
and had no test at all; a charting major can stop drawing without throwing, and every existing check
would still pass. Both bumps merged with the coverage they needed. The browser suite went from 82 to 88.

**The threat model** (#94). STRIDE per element over four trust boundaries, written against this
system rather than a generic one. Severity given twice — as the demo is actually deployed and as the
same finding would read on a network. It numbered eight open items, which is what the rest of the
phase closed against.

**The ingestion credential, the rate limits and the request size bound** (#95, ADR-0017). ADR-0012 §5
had left `POST /api/v1/transactions` open deliberately and named Phase 8. All three landed together
because they are one surface: `X-API-Key` compared in constant time, a token bucket per caller per
category keyed before authentication, and 64 KiB checked on both the declared length and the
delivered bytes. **The largest hole closed was not the one the work was named for** — `POST
/auth/login` runs BCrypt against a supplied password and nothing bounded the attempts.

**CodeQL and the SBOM** (#96). The two controls that did not exist at all. Two SBOMs per run, because
the source tree yields 22 Maven components and the built API image yields 138 — a source-only
document would under-report the Java tree six-fold.

### Two verifications worth more than the code they checked

**The chunked-body test was run with the stream wrapper removed**, and fails — 400, not 413. Without
that experiment it would have been a test passing for the wrong reason, because the
`Content-Length` check catches the ordinary case and the second half would never have been exercised.

**CodeQL's zero was proved by planting a defect.** Zero findings and an empty database look
identical, and this project has been caught three times by exactly that shape. A throwaway controller
concatenating a `@RequestParam` into a native query was pushed on a branch; CodeQL reported
`java/sql-injection [high]` at the exact line, and the branch was closed and deleted without merging.

### A defect found in passing

`bootstrap.sh` wrapped its list of required secrets with a literal backslash-n rather than a line
continuation. The shell splits that into a word `n`, greps `.env` for `^n=`, finds nothing, and
reports a missing secret called `n` — so **`make bootstrap` had been failing and exiting 1 for
anybody whose `.env` was already complete.** Committed separately from the phase's work.

### Two threat-model items opened

**T-09**, because closing T-01 created it: a shared key authenticates a caller without identifying
which one, so two pipelines are indistinguishable and neither becomes an actor in the audit trail.
**S-06**, password guessing by volume, which had never had a row because the identical-refusal
behaviour it lives beside reads like a defence and is not one.

The threat model now states the rule: an item that closes says when, an item its fix created gets its
own number, and a list that only ever shrinks is not being kept.

### Evidence

`PROJECT_STATE.md` §"2026-08-31 — Phase 8 closed" holds the table. In short: 259 unit and 322
integration tests in `apps/api`, 41 unit and 88 browser tests in `apps/web`, contracts and docs
checks clean, CodeQL 0 results over three languages, SBOMs at 262 / 118 / 71 components per image,
and all eight workflows green on `main`.

### What was deliberately not done

`/actuator/prometheus` is still unauthenticated (T-04). ADR-0017 §4 declines to bundle it: the fix is
a management port not published to the host, and it changes what Prometheus scrapes, what
`compose.yaml` publishes, what `make smoke` asserts and what the runbooks say. Two decisions in one
commit is how one of them stops being reviewed.

No load test, no measured latency, no throughput figure. The rate-limit defaults are starting points
chosen for a demo, labelled as such in ADR-0017 §2, and Phase 9 is what measures.

---

---

## 2026-09-01 — Phase 9 opened: the benchmark, a measured index, the CodeQL correction, and a stop

| Field           | Value                                                                                                                 |
| --------------- | --------------------------------------------------------------------------------------------------------------------- |
| Start / end UTC | 2026-08-31T23:15Z / 2026-09-01T00:20Z                                                                                 |
| Starting SHA    | `4333b12` on `main`                                                                                                   |
| Ending SHA      | `8692df9` on `main`, plus this checkpoint                                                                             |
| Objective       | Open Phase 9 with benchmarks and a measured optimization; stopped deliberately at the user's direction, window at 89% |

### Work completed

**The benchmark harness.** `make bench` and `sf.ps1 bench` drive the running stack over HTTP and
write `docs/performance/BENCHMARK.md` with the machine, the container runtime and the dataset it ran
against — a latency figure without those three is not reproducible. It **paces reads under the rate
allowance rather than raising the limiter to flatter itself**, runs ingestion inside the configured
burst, and lists what it does not measure.

**One measured optimization.** `GET /transactions` was the slowest of five endpoints by a factor of
five. The SQL came from PostgreSQL's own statement log rather than reconstruction, and
`EXPLAIN (ANALYZE, BUFFERS)` showed the cost was **not the sort**: a correlated subquery resolving
each transaction's latest assessment version ran **34,629 times** for 98% of the buffers, to return
fifty rows, because nothing indexed `ORDER BY occurred_at DESC, id DESC`. `V12` adds it.

    page query        68.0 ms → 5.0 ms
    buffer hits       71,256  → 6,155
    endpoint p50      116 ms  → 32 ms   (page 20)

**The Phase 8 CodeQL claim was wrong, and was corrected.** The gate recorded "CodeQL: 0 results",
read from the analyses on the **pull-request merge refs**. The run against `refs/heads/main` after
the merge reported **12 alerts**. A pull-request analysis and a branch analysis do not answer the
same question. All twelve were triaged: `js/file-system-race` and `java/log-injection` fixed in
code, `java/spring-disabled-csrf-protection` dismissed as a false positive with its evidence, nine
`java/unused-parameter` notes dismissed as framework-mandated signatures. **0 open at the end.**

**All three Phase 8 controls were confirmed against the real compose stack**, not only
Testcontainers: ingestion answers 401 without the key and 202 with it, the login limiter returns 429
with `Retry-After: 5` after ten attempts, and a 70 KB body is refused 413.

### Two defects the work found in itself

**The benchmark harness swallowed a failed query.** A section asked for a column that does not exist
and `psql`'s non-zero exit was discarded, so the report rendered an **empty section rather than
failing** — a measured zero that was not measured. `ON_ERROR_STOP=1` and a `required` flag now make
it loud. Caught before any number reached a document.

**`MigrationIT` pins the exact migration list and the deliberate indexes**, so it failed on `V12`
until both were added. That is the guard working, and it is worth knowing before the next migration.

### The requirement recorded at the end, and why it is here

At the user's direction, a binding Phase 9 requirement was written into
"Required before v1 — carried forward" §2 and into the implementation plan's Phase 9 gate: **the
README must be a polished, professional, industry-standard public landing page**, fit for
recruiters, hiring managers, senior engineers and open-source reviewers, and **technical
completeness alone does not satisfy it**.

It exists because the README has drifted into a development diary honestly — each phase added its
evidence to it, and nothing ever took anything out. The rewrite is editorial and structural, not a
trim, and **nothing useful may be deleted merely to shorten it**: detail moves into `docs/` and is
linked. Phase 9 cannot be marked complete until the result has been read from an outside engineer's
or recruiter's point of view and that review recorded — `make docs-check` passing is not that
review, because a build log with working links passes it.

### The exact resume point

**Start with the README rewrite.** It is the largest remaining piece of Phase 9 and it is now a gate
criterion; the full text of the requirement is `PROJECT_STATE.md` §"Required before v1" §2. Then the
clean-clone verification, which is the same work — it checks the commands the rewritten README ends
up publishing, and **neither `make bootstrap` (which gained a required secret in Phase 8) nor
`make bench` (which is new) has ever been run from an actually-empty clone**. Then a decision on
whether one measured optimization is enough for the phase.

**One known trap for that third item:** `make bench` adds alert-raising rows on every run, so the
local dataset drifts under measurement, and that database already carries 7,260 `FAILED`
transactions and 13,455 `degraded` assessments from the h2c defect.

### State at the stop

Working tree clean, `main` in sync with `origin/main`, no pull request open, all eight workflows
green, 0 open code-scanning alerts. Nothing was left half-finished.

### What was deliberately not started

The README rewrite, the clean-clone validation, further benchmarking, and any Phase 10 work. The
session stopped on instruction with the usage window at 89%, not because anything blocked.

---

## 2026-09-01 — Phase 9: the README becomes a landing page, and three documents get written to hold what it stopped carrying

| Field           | Value                                                                                                                         |
| --------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| Start / end UTC | 2026-09-01T01:10Z / 2026-09-01T02:20Z                                                                                         |
| Starting SHA    | `9a2d900` on `main`                                                                                                           |
| Ending SHA      | recorded at the merge; the work landed as a pull request from `docs/readme-landing-page`                                      |
| Objective       | The first incomplete item in "Next three actions": the README landing-page rewrite, which is a binding Phase 9 gate criterion |

### Work completed

**Three documents first, because the requirement forbids shortening by deletion.** "Required before
v1" §2 is explicit that detail comes out of the README and goes into `docs/`, and that dropping it
is the failure the clause exists to prevent. All three are names from §24 of the build prompt, so
this filled gaps in the required set rather than inventing a filing scheme:

- `docs/testing/TEST_RESULTS.md` — the four dated test blocks, the coverage table and its ratchet
  policy, the two resilience drills, the log-redaction result, and what the suites do not cover.
- `docs/operations/OBSERVABILITY.md` — dashboards, the thirteen alerting rules, the ten runbooks,
  tracing across the asynchronous hop, and what is deliberately not instrumented.
- `docs/operations/TROUBLESHOOTING.md` — every symptom the README carried, plus two that were only
  in `PROJECT_STATE.md`: the reference-data check that refuses to start against a truncated `users`
  table, and the safe way back to a clean database.

**Then the rewrite.** 724 lines of front page became 647, and the change is structural rather than
arithmetic: the phase-status table, the "what runs today" narrative, the four dated evidence blocks,
the pull-request chronology and the agent-workflow detail are gone from the landing page and live
where a reader who wants them will find them.

**Five diagrams, where there had been two.** The transaction-to-alert sequence, condensed from the
architecture document; outbox delivery as a state machine, drawn from `OutboxStatus` and the relay's
own configuration; the investigation state machine, drawn from `AlertTransitions` rather than from
memory; and the existing architecture and local-deployment views. All five parse against mermaid
11.17.2 — validated by installing mermaid in a scratch directory and calling `mermaid.parse` on each
fenced block, because a diagram that does not render is worse than no diagram on a page a recruiter
opens once.

**The external-reader review is recorded** under "Acceptance criteria status — Phase 9 gate" in
`PROJECT_STATE.md`, which is the criterion the plan says Phase 9 cannot close without. It states its
own limit: it is a careful read against the criterion by the session that wrote the file, and no
outside person has read it.

### What the review found, and why it matters more than the rewrite

**The README had drifted from its own benchmark report.** It claimed a burst was accepted in 0.42 s
and persisted 3.4 s later; `docs/performance/BENCHMARK.md` says **0.78 s** and **5.0 s**. Both
numbers were real once. Neither was current, and nothing had caught it because `make docs-check`
checks links and placeholders — a stale figure with a working link passes it.

**Two other numbers were dropped rather than guessed.** `make smoke` was described as 23 checks,
last measured 2026-08-28 and before Phase 8 changed the endpoints it asserts; and a link count in a
summary table would be read as current when it changes with every document added. The clean-clone
pass measures the first properly.

**The deployment diagram showed seven containers** while two other sections of the same file said
ten. It now shows all ten, including the one-shot `kafka-topics` service — which is worth drawing,
because the step it performs is the one that was once missing while every health check passed.

**A drive-by fix:** `docs/planning/IMPLEMENTATION_PLAN.md` had the Phase 10 heading twice.

### The lesson worth carrying forward

**A documentation checker that passes is not a documentation review.** Three of the five defects the
review found were numbers that were true when written and false when read, and every one of them
passed `make docs-check` on every run since. The gate criterion asking for a recorded human-
perspective read is not ceremony; it is the only check in this repository that could have found
them.

### The exact resume point

**The clean-clone verification.** Clone into an empty directory and run every command the rewritten
README publishes, in the order it publishes them. **Two traps, neither ever exercised from an
actually-empty clone:** `make bootstrap` gained `SENTINELFLOW_INGEST_API_KEY` in Phase 8 along with
the fix for the exit-1-against-an-existing-`.env` bug, and `make bench` is new. `bun install` on a
deep Windows path is the third thing to watch.

Then: whether one measured optimization is enough for the phase — `GET /alerts` now has the worst
p99 — and the two small deliverables still open, the demo walkthrough with more screenshots and the
deployment ADR.

### State at the stop

Verified before the push, not assumed: `bun run format:check` clean; `bun scripts/dev/check-docs.mjs`
274 relative links across 53 Markdown files, 0 broken, no placeholders; all five README mermaid
blocks parsed.

---

## 2026-09-01 — Phase 9 continued: the clean clone finds four things nobody could have found from here

| Field           | Value                                                                                           |
| --------------- | ----------------------------------------------------------------------------------------------- |
| Start / end UTC | 2026-09-01T02:25Z / 2026-09-01T03:45Z                                                           |
| Starting SHA    | `b6ff004` on `main`                                                                             |
| Ending SHA      | recorded at the merge of the clean-clone documentation pull request                             |
| Objective       | "Next three actions" items 1 and 3: the clean-clone verification, and the optimization decision |

### Work completed

**A clone into an empty directory, with empty Docker volumes, and every command the README
publishes.** The existing stack was stopped first, keeping its volumes; the clean run used its own
Compose project name so it could not attach to them.

Everything the README promises works: `make bootstrap` generates all five secrets and is idempotent,
`make up` brings up all ten containers healthy, `make smoke` passes **23 of 23** through both the
shell and PowerShell surfaces, `make seed` writes 2,105 transactions with a checksum, and the
pipeline behind it assessed **2,105 of 2,105 with none degraded and none failed** on a database that
had not existed twenty minutes earlier. `make bench` ran, `bun install --frozen-lockfile` took 11
seconds, and the contract and documentation checks passed.

**Four defects, each merged with the run that proves it:**

| What                                                                                                          | PR   |
| ------------------------------------------------------------------------------------------------------------- | ---- |
| The PowerShell bootstrap generated **2 of 5 secrets**, so a Windows first-run wrote an `.env` compose refuses | #107 |
| `SCENARIO=poison-event make replay` exits **127** in Git Bash — MSYS path conversion                          | #105 |
| `make bench` misreported its own dataset — 27 alerts published beside a latency measured against 119          | #104 |
| `make bench` left the tree failing `make format-check`                                                        | #106 |

A fifth was found before any of them and merged the same way: `RiskAssessmentWorkflowIT` failed three
tests on a documentation-only pull request, because CI ran at 02:35 UTC and `OFF_HOURS` fires between
02:00 and 04:59. It was reproduced on unmodified `main` in the same window, fixed, and re-run — 13/13
(#103).

**The optimization question is answered rather than left open.** Three benchmark runs now exist and
they say the same thing three ways: `p99` equals `max` in all ten rows of both reports, because
nearest-rank p99 of thirty samples **is** the maximum; the same endpoint on two comparable datasets
gave 98.15 ms and 33.78 ms an hour apart; and a ninefold difference in dataset size moved the p50 by
2.5 ms. One measured optimization is enough, and what would have to change before that is worth
revisiting is written down.

### The lesson worth carrying forward

**Every defect this session found lives in a place no existing check can reach**, and they divide
into two kinds.

Three of them are in the gap between _a working tree that already exists_ and _a stranger's first
command_. The PowerShell bootstrap bug is the sharpest: the broken branch is the one that only runs
when `.env` is absent, which is never true for anybody who has worked here. It had been broken since
Phase 8 added the ingestion key, on the path the README publishes for Windows, and the script
reported success while writing a file the stack refuses.

The other kind is time. `OFF_HOURS` made a suite fail for three hours in every day and pass for the
other twenty-one, and the only reason it had never been seen is that no pull request had happened to
run overnight.

**A clean clone and an unusual clock are both just inputs nothing had tried.** The build was green
throughout all of it.

### The exact resume point

Phase 9 has **six of eight criteria met**. The two open ones are small and both are in the gate
table: the demo walkthrough with screenshots beyond the two that exist, and the ADR for the
deployment and local-first strategy. The phase cannot close with either open.

After that, Phase 10 — and "Required before v1" §1, operator identity, is the largest remaining
piece of work in the project and the one Phase 10 cannot ship without.

### State at the stop

Six pull requests merged, #102 through #107, every one with green CI on `main`. Working tree clean,
`main` in sync. The clean-clone stack and its throwaway clones were removed and the original stack
was restored to its own volumes, which were never touched.

---
