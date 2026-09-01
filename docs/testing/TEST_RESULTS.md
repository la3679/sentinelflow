# Test results

Every figure on this page came from a command that was actually run, on the date recorded beside
it. Nothing here is estimated, and a figure that has not been re-measured keeps its original date
rather than being quietly refreshed.

This is the reader-facing record. The per-phase gate evidence, including what each run was allowed
to close, lives in [`PROJECT_STATE.md`](../../PROJECT_STATE.md) under "Test and verification
evidence".

## Reproducing these numbers

```bash
make test              # every standard suite
make test-integration  # Testcontainers PostgreSQL 18.6 and Kafka 4.2.1
make test-e2e          # Playwright, accessibility, responsive
make lint              # eslint · ruff · mypy · spotless
make format-check
make contracts-check
make docs-check
make security          # gitleaks over the full history
make smoke             # against a running stack
```

On Windows without `make`, every target is available as `.\scripts\dev\sf.ps1 <target>`.

## Current verified results

**2026-08-31**, on the commit that closed Phase 8, on JDK 25.0.4.1+1:

| Suite                      | Command                                 | Result                                                   |
| -------------------------- | --------------------------------------- | -------------------------------------------------------- |
| API — full verify          | `./mvnw verify` in `apps/api`           | **259 unit + 322 integration passed**, 0 failures        |
| API — coverage             | JaCoCo, both suites                     | **gates met** — LINE 0.80, BRANCH 0.70                   |
| Scoring — unit             | `uv run pytest` in `apps/scoring`       | **187 passed** in 139.59 s                               |
| Scoring — lint and types   | `ruff check` · `ruff format` · `mypy`   | **PASS** — no issues over 46 source files                |
| Console — unit             | `bun run test` in `apps/web`            | **41 passed**                                            |
| Console — browser and a11y | `bun run test:e2e` in `apps/web`        | **88 passed** — axe clean, eight routes, two viewports   |
| Contracts                  | `bun scripts/dev/check-contracts.mjs`   | **PASS** — two OpenAPI documents and the AsyncAPI one    |
| Documentation              | `bun scripts/dev/check-docs.mjs`        | **PASS** — 234 links across 49 files, 0 broken           |
| Formatting                 | `bun run format:check`                  | **PASS**                                                 |
| Bootstrap                  | `bash scripts/dev/bootstrap.sh --check` | **complete**                                             |
| Static analysis            | CodeQL on `refs/heads/main`             | **0 results**, CodeQL 2.26.4, `security-and-quality`     |
| Container scanning         | Trivy in `ci-containers.yml`            | **clean** on all three images, fixable HIGH and CRITICAL |
| Secret scanning            | gitleaks in `security-scan.yml`         | **clean** over full history                              |
| Everything on `main`       | eight workflows                         | **all green**                                            |

The API's integration suites run against **real PostgreSQL 18.6 and real Kafka 4.2.1** in
Testcontainers, on the GitHub runner as well as locally. H2 is never accepted as evidence, and
neither is a mocked broker.

### Two results that are checks on a check

**CodeQL's zero was proved by planting a defect.** Zero results and an empty database look
identical on a dashboard. A throwaway branch carrying a deliberate SQL-injection defect was scanned
and reported `java/sql-injection [high]` at the exact line, then deleted. This project has been
caught three times by a zero that was really an absence — an unregistered Prometheus series, Kafka
topics nothing created behind green health checks, and a scoring client refused at every call while
every suite passed.

**The chunked-body size test was run with the stream wrapper removed**, and fails — 400, not 413.
That is the only thing distinguishing a test that exercises the size cap's second half from a test
the `Content-Length` check was quietly passing.

## Coverage

| Component      | Measured                           | Gate                         | Measured on |
| -------------- | ---------------------------------- | ---------------------------- | ----------- |
| `apps/api`     | line 0.8955, branch 0.7931         | LINE 0.80, BRANCH 0.70       | 2026-08-29  |
| `apps/scoring` | 97.36% statements                  | 90% statements               | 2026-08-27  |
| `apps/web`     | 26.79% statements, 18.61% branches | 25% statements, 17% branches | 2026-08-29  |

**Every gate is a ratchet** — measured, then set below the measurement, raised only when a change
genuinely raises coverage, and never lowered to go green. `apps/api` was raised on 2026-08-27 from
LINE 0.70 and BRANCH 0.60 after the assessment workflow measured 85.7% and 76.9%.

**The console's number is not a claim that it is a quarter tested.** Most of its behaviour is
asserted by Playwright against a real browser, because focus visibility, keyboard operation,
contrast and axe cannot be checked in jsdom. The unit gate exists to stop that layer silently
shrinking, not to describe how well the console is tested.

## Resilience drills

Both run inside `make test-integration`, against a real broker and a real database.

| Drill                  | What it observed                                                                                                                                                             |
| ---------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ScoringOutageDrillIT` | 30 transactions assessed while scoring refused; **30/30 degraded** with no model score; 0 dead-lettered; **5 records' worth of HTTP attempts, not 30**; recovery automatic   |
| `BrokerOutageDrillIT`  | Every ingest still `202` with the broker frozen; 6 events held `PENDING` with `last_error` recorded; **0 `FAILED`**; the backlog drained to exactly one ledger row per event |

The circuit breaker is what turns 30 attempts into 5, and that ratio is the point of the first
drill: an outage's cost is bounded by the breaker rather than by the number of transactions.

## Log redaction

`LogRedactionIT` runs at `logging.level.root=DEBUG` over five paths. Widening it from "the
application's package and Spring Web" to the root logger turned 4 assertions into 8 and found
**four leaks no narrower configuration could have shown**: Hibernate's entity dumps carrying an
amount, a device handle, an outbox payload and an analyst's note; `AlertNoteRequest` printing the
note through Spring's own read line; and `TransactionResponse` being safe only because a framework
log line truncates at 100 characters.

The bearer token and the password never appeared, which is the other half of the result. A test
that excuses the loggers it cannot satisfy is testing its own exclusions.

## Earlier runs

Kept because a number's date is part of the number.

| Date       | What it measured                                       | Headline                                                                |
| ---------- | ------------------------------------------------------ | ----------------------------------------------------------------------- |
| 2026-08-31 | Phase 7 close — drills, runbooks, alerting rules       | 233 unit + 309 integration; 13/13 Prometheus rules parsed and evaluated |
| 2026-08-29 | Phase 6 close — the console against the real API       | 226 unit + 290 integration; 82 Playwright tests, axe clean              |
| 2026-08-28 | Phase 5 close — the reporting endpoints under contract | 189 unit + 250 integration; 89.7% lines, 79.5% branches; smoke 23/23    |
| 2026-08-27 | The assessment workflow, joined and persisted          | 147 unit + 172 integration; 85.7% lines; scoring 171 passed at 97.36%   |
| 2026-08-26 | Phase 3 close — ingestion, the outbox and Kafka        | 57 unit + 116 integration; 80.5% lines, 70.0% branches                  |

## What the suites do not cover

Running the stack is not the same as running the suites, and only one of them is what a demo runs
on. Three defects were found by running the compose stack that every green build was blind to:
nothing created the Kafka topics, the scoring client negotiated HTTP/2 against an HTTP/1.1-only
service, and two PowerShell targets had never worked. Each is fixed, and
[`docs/operations/RUNBOOKS.md`](../operations/RUNBOOKS.md) carries the diagnostic sequence for the
second one.

Performance is measured separately and reported with its method in
[`docs/performance/BENCHMARK.md`](../performance/BENCHMARK.md). No latency, throughput or
false-positive figure is claimed anywhere in this repository that did not come from that harness.

Model quality is reported in [`docs/ml/MODEL_CARD.md`](../ml/MODEL_CARD.md), which is regenerated by
the training run rather than edited by hand.

**Two verification items remain outstanding and are a person's job**, recorded here so that no
automated result is read as covering them: a screen-reader pass, and a manual authenticated
walkthrough of the console in a browser. axe finds roughly a third of real accessibility issues,
and every accessibility check in this repository is one of the cheaper two-thirds.
