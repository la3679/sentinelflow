# SentinelFlow Project State

> Authoritative resume file for fresh sessions. Update before every stop, compaction, tool
> handoff, and push checkpoint.

## Resume instructions

1. Read this file completely.
2. Run the verification commands in "Session startup commands".
3. Confirm branch, HEAD, and remote before editing.
4. Read **"Required before v1 — carried forward"**. It holds work that is committed rather than
   optional, and two items no session may ever mark complete on a person's behalf.
5. Continue from "Next three actions"; do not restart completed phases.

## Snapshot

| Field                | Value                                                                                                                                                                       |
| -------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Last updated UTC     | 2026-08-31T16:05Z                                                                                                                                                           |
| Updated by           | Claude                                                                                                                                                                      |
| Overall status       | active — **Phase 7 is closed with all four gate criteria evidenced**; Phase 8 is next                                                                                       |
| Current phase        | Phase 8 — security and quality hardening (not started)                                                                                                                      |
| Current task         | **none in progress.** Phase 7's six deliverables are merged and its gate is claimed. Phase 8 has not been opened; see "Next three actions"                                  |
| GitHub repository    | <https://github.com/la3679/sentinelflow>                                                                                                                                    |
| Visibility           | **PUBLIC** since 2026-08-25, after both scans passed                                                                                                                        |
| Default branch       | `main` — **protected** since 2026-08-25 (ruleset `main protection`, id `21493410`)                                                                                          |
| Working branch       | `main`                                                                                                                                                                      |
| Local clone verified | **yes**                                                                                                                                                                     |
| Local workspace      | a `sentinelflow/` folder inside the user's Documents workspace. The absolute path is recorded in the git-ignored `.claude/runtime/worktree.json`                            |
| Lovable sync branch  | `main` — **generation retired**, see "Lovable" below                                                                                                                        |
| Open PRs             | seven Dependabot PRs, [#75](https://github.com/la3679/sentinelflow/pull/75) to [#81](https://github.com/la3679/sentinelflow/pull/81), untriaged. No SentinelFlow PR is open |
| Latest release       | none                                                                                                                                                                        |

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
- [x] **Phase 4 — synthetic data and scoring**
- [x] **Phase 5 — alerts and investigations**
- [x] **Phase 6 — operations frontend**
- [x] **Phase 7 — observability and resilience**
- [ ] **Phase 8 — security and quality hardening** ← next
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

## Completed — Phase 4, merged as PRs [#29](https://github.com/la3679/sentinelflow/pull/29) through [#47](https://github.com/la3679/sentinelflow/pull/47)

All fifteen pieces landed, the last two in [#47](https://github.com/la3679/sentinelflow/pull/47): the workflow that joins the ruleset, the client and the policy into a persisted assessment, and the replay that demonstrates it.

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

**The 0–100 score has to be calibrated underneath, which follows from ADR-0008 §4 rather than from
taste.** One threshold, owned by the API, has to mean the same thing under a model and under a
rules-only degraded assessment, which requires a scale that is stable across model versions. The
units stay the contract's: `sentinelflow-scoring.yaml` already fixes `modelScore` at 0 to 100 and
says it is **not** a probability, because the positive class is "belongs to a planted shape" and not
"is fraud". Calibration is a property of the mapping rather than of the units, so the two are not in
tension — a first draft of the ADR said "returns a calibrated probability", was caught against the
contract before merge, and the correction is recorded in the ADR's alternatives.

That rules `IsolationForest` out of production before anything is trained — its anomaly score is
unbounded and dataset-relative, and giving it a stable 0–100 meaning means calibrating against the
labels it was included for being able to ignore. It stays as an unsupervised comparison.

**The selection rule is fixed in advance so it cannot be rationalised afterwards.** PR-AUC is the
headline and accuracy is never one; a model ships only if it beats the rules baseline by a stated
margin, and **the rules ship alone if none does**; a gap inside the cross-validation fold spread is
fold noise and goes to logistic regression; and the operating point is chosen against an
alert-volume budget rather than by maximising F1, because an analyst team is a fixed-capacity queue.

**The account-context assembler, merged as PR
[#37](https://github.com/la3679/sentinelflow/pull/37).** One implementation behind both training and
serving. Three properties are enforced by the query rather than by convention: the window ends
**strictly** before the scored transaction's own `occurredAt` (which also excludes it from its own
history, without needing its identifier); ordering breaks ties on `id`, because with `occurredAt`
alone the rows surviving a truncation would vary between plans and the same transaction would score
differently on a retry; and truncation is detected by asking for one row more than the cap, with
exactly the cap reported as complete rather than truncated.

**The balance is a parameter, which is the one place ADR-0010's "same assembler" guarantee is
narrower than it reads.** `transactions` has no balance-after column, so a historical balance is not
reconstructible from the schema. The seam exists so an export cannot inherit a wrong balance
silently — see the export below for why it is currently unused.

**The labelled export, merged as PR
[#39](https://github.com/la3679/sentinelflow/pull/39).** One JSON object per line: the exact
`ScoreRequest` plus the planted `ScenarioType`, with a manifest carrying the generator version, seed,
profile, context version, lookback, class distribution, `negativeLabel`, scenario checksum and a
SHA-256 over the file. Written to `data/generated/training/`, which `.gitignore` already excludes.

**Labels are recovered, not read.** The export regenerates from the same seed and joins to the
stored rows by idempotency key — which the generator derives from the seed, a sequence number and
the transaction's offset within the window, never from a clock. So `export()` takes no instant: the
window end is immaterial, and the signature says so rather than implying it must match.

**A shifted join is the failure that would not announce itself.** It would produce a complete,
well-formed, entirely mislabelled file; the trainer would run and the model would simply be
mediocre. Both halves therefore regenerate through one `ScenarioDataset`, and the integration test
re-derives the join backwards — line, to `transactionId`, to the stored row, to its key — because
two independent routes to the same answer is the only check a shifted join fails.

**It calls the runtime assembler, balance read included**, which is exact rather than approximate
because **nothing in this application ever changes an account balance**. A test asserts that
invariant directly, so introducing balance mutation fails it and forces the export onto the seam
above rather than leaving a feature quietly wrong.

**Reproducible training, the registry and the model card, merged as PR
[#41](https://github.com/la3679/sentinelflow/pull/41).** An offline command — `make train` — that
reads the labelled export, compares four candidates, applies ADR-0010 §5's rule, and writes a
registry entry with its manifest, metrics, plots and a generated card.

**Features come from the serving extractor**, not from anything in the trainer: the loader parses
each line back into a `ScoreRequest` and calls `features.extract`, and a test asserts the values
match rather than merely the shapes. A second feature implementation would fail it.

**The measured run** — re-run 2026-08-27 against the shipped ruleset, seed `20260826`, profile
`LOCAL`, 20,707 examples with 707 planted, 7,876 training rows and 2,499 holdout rows carrying 75
positives. The model itself did not move: same features, same split, same seed, byte-identical
artifact. Only the floor did, and it went **up** — see "The floor was in the wrong place" below.

| Model                    | Holdout PR-AUC | CV mean | CV spread | Outcome        |
| ------------------------ | -------------- | ------- | --------- | -------------- |
| `rules-baseline`         | 0.2611         | —       | —         | the floor      |
| `logistic-regression`    | **0.8327**     | 0.8867  | 0.0723    | **selected**   |
| `hist-gradient-boosting` | 0.8081         | 0.8841  | 0.0814    | qualified      |
| `isolation-forest`       | 0.7154         | 0.7840  | 0.1035    | never eligible |

### Four defects, every one found by running it

**The split produced an empty holdout, twice.** An unstratified quarter of accounts contained no
planted shape at all. Stratifying on "carries a shape" was not enough either: all seven held-out
positive accounts had their shapes before the time cutoff, leaving 40 late positives in the dataset
and none in the holdout. It stratifies on **"carries a shape after the cutoff"** now — the precise
property the holdout needs. Training is also restricted to before the cutoff, because holding out
later transactions means nothing if the model was fitted on that period.

**One threshold shared across candidates was wrong.** Two models can rank identically and still
place their scores at completely different absolute values, so a threshold from one applied to
another compares two different alert volumes — which is the whole point of budgeting. Each candidate
now takes its operating point from its own out-of-fold distribution.

**A holdout of three positives.** The DEMO profile produces one, and the selected model's PR-AUC
moved from 0.06 to 0.39 on the difference between finding one of them and none. Nothing there was
fabricated and publishing it would still have been dishonest — evidence incapable of supporting a
conclusion. There is a floor of **20 positives** now, below which nothing is promoted, and the card
states the count either way.

**The card printed `100.00` for a threshold of `99.99986221`.** No score reaches exactly 100, so a
reader applying `100.00` would have alerted on nothing. Quoted at full precision now, with a test.

### Two things the plots taught

The first reliability curve used quantile bins, which put every bin in the crowded region near zero
and said nothing about the top of the scale — the only region an operating point is ever in. Uniform
bins fixed it, and markers are sized by bin count so the jagged middle reads as sparsity rather than
mis-calibration.

### joblib warns on every model load, on the serving path

**joblib 1.5.3 assigns to `array.shape`, which NumPy 2.5.2 deprecated.** Measured with and without
compression. When NumPy removes the behaviour, the scoring service stops being able to load a model.
Silenced narrowly by message so `filterwarnings = ["error"]` survives everywhere else, and recorded
as an open item in the research log. ADR-0004 already pins the Python version to joblib's support
window, so this is the second reason to watch that dependency.

### Dependencies, and where they live

scikit-learn 1.9.0, numpy 2.5.2 and joblib 1.5.3 are **runtime** dependencies at exactly ADR-0004's
versions, because serving needs all three. matplotlib 3.11.1 is in a **training-only group** so the
serving image never carries a plotting library on the request path — verified by running
`uv sync --no-dev` and confirming matplotlib absent while sklearn and joblib are present. The `dev`
group includes the training group, so CI still has it. **pandas is deliberately not installed**:
ADR-0004's table pins a version to use if a need appears, and none has. Recorded as R-2026-08-26-01.

### The two items the last checkpoint left owed are resolved

- **`.gitignore` versus ADR-0010 §6.** The ADR is later and binds, so registry entries are committed
  and the old `models/*.joblib` rule is gone, with the reasoning left in its place. It would not have
  matched the nested path anyway — a single `*` does not cross a directory separator — so the
  artifact was already being committed by accident rather than by choice. The 4 KB artifact is well
  inside the command-enforced ceiling.
- **The static-balance limitation** is stated in both the model card and `EVALUATION.md`.

### The off-hours shape was not in the off hours, and had never been

Merged as PR [#38](https://github.com/la3679/sentinelflow/pull/38), found while building the export.

`OFF_HOURS_NEW_DEVICE` landed two hours after whatever time of day the run began. Measured before
the fix, seed 20260826, profile CI:

| Window start           | UTC hours the shape landed on |
| ---------------------- | ----------------------------- |
| `2026-08-12T00:00:00Z` | 2, 3                          |
| `2026-08-12T12:00:00Z` | 14, 15                        |
| `2026-08-12T17:23:41Z` | 19, 21                        |

The production caller is `SeedRunner`, which passes `Instant.now()`, so midnight — the one window
start that made it correct — essentially never happens. In every real seeded demo the planted
"off-hours" transaction sat at an ordinary hour, and `is_off_hours` never fired on it.

**The cause was two guarantees in conflict, one of them unstated.** Offsets from the window start
are what make the dataset reproducible and what the manifest's checksum covers; an off-hours shape
is defined by a time of day, which cannot be expressed as an offset from an arbitrary instant. The
code assumed a midnight-aligned window start and never said so. `generate()` now anchors it, so the
precondition holds for every caller rather than for the ones who remember.

**The existing test passed for the wrong reason**, which is the more useful half. It asserted
`offset().toHours() % 24` was between 2 and 3 — a property of the offset arithmetic, not of when the
transaction occurred — and the fixture window began at midnight, where the two agree exactly. It
reads the hour from `occurredAt` now, and a second test runs four window starts including 12:00,
17:23:41 and 23:59:59.

`GENERATOR_VERSION` moved 1.0.0 → 1.1.0: the same seed now produces different traffic, and the
manifest records which generator drew a dataset precisely so that is attributable.

### Two things the training work has to resolve rather than inherit

- **`.gitignore` and ADR-0010 §6 disagree about the model artifact.** The ignore rule
  `apps/scoring/models/*.joblib` predates the ADR, which says the artifact is committed so a demo can
  score without someone running a training job first. ADRs bind until superseded, so the ignore rule
  is what changes — but it is a deliberate decision to make in the training commit, with the ADR
  noting that it supersedes the earlier rule, rather than a line quietly deleted.
- **`balanceDrainRatio` is measured against a balance that never moves.** Since nothing mutates a
  balance, each transaction's drain ratio is against the account's opening balance rather than a
  running one — so an `ACCOUNT_DRAIN`'s three withdrawals read as three independent partial drains
  rather than as one cumulative emptying. The velocity and one-hour-sum features are what actually
  carry that shape. Not a defect in the feature, which computes what its name says; a limitation of
  what the schema records, and one the model card and `EVALUATION.md` must state rather than let a
  reader assume otherwise.

### The inference API, merged as PR [#43](https://github.com/la3679/sentinelflow/pull/43)

**The model is reachable.** `POST /v1/score` returns the 0-to-100 score, the model and feature
versions, bounded reason codes, a measured inference duration and the extractor's warnings;
`GET /v1/model` publishes the manifest's identity and the selected model's holdout figures, read
from the metrics document beside the artifact rather than restated anywhere. Both were specified in
Phase 4's third piece and implemented against that specification.

**Reasons are the linear model taken apart, not an approximation of it.** `coefficient x
standardised value`, averaged across the three calibrated folds, on the log-odds scale before
calibration — which for a logistic regression is not a model explanation technique, it is the model.
Calibration is monotone, so the contributions explain the ranking and deliberately do not sum to the
score, and the README says so rather than leaving a reader to assume they do.

Three rules inside that, each of which is the difference between arithmetic and an explanation an
analyst can act on: an indicator is reported **only when it fired**, because `is_new_device` at 0.0
still has a contribution and `NEW_DEVICE` on a device the account has always used says the opposite
of what happened; direction (`_HIGH`, `_LOW`) describes the **feature**, not the contribution, since
a below-average value under a negative coefficient pushes the score up; and a model that cannot be
decomposed returns an empty list and a warning, because an invented explanation is worse than an
absent one.

**Loading is a set of refusals.** One entry serves or the process does not start: the checksum, the
feature version, the recorded column order, and that the metrics beside an artifact describe that
artifact. Two entries at the running feature version are a refusal rather than a tie-break — picking
either would make which model produced a score depend on directory order — with a configuration pin
as the escape hatch, and half a pin rejected at startup. An empty registry is deliberately not a
refusal: the service runs, reports `modelLoaded: false`, returns a retryable 503, and the API
degrades to rules.

### Three defects the inference work found

- **The training suite was overwriting `docs/ml/MODEL_CARD.md` on every run.** `--docs` defaults to
  the repository's own documentation tree and the end-to-end tests never passed it, so every
  `make test-scoring` replaced the published card with one for the TEST fixture: 1,280 examples where
  the real card records 20,707, and a different profile, holdout and operating point. Invisible
  because the file is generated and a regenerated generated file looks exactly like one. Found by
  `git status` after a test run. Fixed in its own commit, with a test that asserts `--docs` is
  honoured so the reason is written down rather than remembered.
- **`/health/ready` returned `model_loaded` where the contract says `modelLoaded`.** The only
  endpoint whose body was not camel case, since Phase 1. Nothing had noticed because the conformance
  suite checked **request** shapes only. There is now a response-side one, which is worth more than
  the one-line fix it produced.
- **The image never carried `models/`.** ADR-0010 §6 commits the artifact so a demo can score without
  a training run first, and the Dockerfile copied only the virtual environment — so the promise held
  everywhere except where the service runs. `.dockerignore` still carried a `models/*.joblib` rule
  expressing the opposite intent, which had never matched the nested path anyway.

### The column-order check had no symptom, which is why it is a refusal

The registry validated the checksum and the feature version and its own docstring claimed it also
validated the column order. It did not. A model handed its columns in a different order still returns
a number, still between 0 and 100, and it is an answer about different quantities — no exception, no
warning, nothing downstream that would ever notice.

`FEATURE_NAMES` now declares the canonical order and `registry.load` **requires** the caller to
supply the order it is about to use. Required rather than optional, because a check that has to be
asked for is one that will eventually not be. A test asserts the declared tuple against what
`extract` actually returns, so a declared list that nothing produces cannot pass for a check either.

### The rules baseline, merged as PR [#44](https://github.com/la3679/sentinelflow/pull/44)

**Seven transparent indicators in `apps/api`**, each contributing a configured weight: velocity over
five minutes, the amount against this account's own recent mean, a device the account has not used, a
country change, the small hours, a large share of the balance, and distinct merchants within the
hour. Summed and clipped to the contract's scale, returned with the reasons that produced it, and
sourced as `RULE` so an analyst can tell a rule from a model attribution.

**In `apps/api` because that is where it has to run.** ADR-0002 §3 assigns deterministic rule scoring
here and ADR-0008 §3 is why: a ruleset reached over the network could not answer "the network is
down". Thresholds and weights are configuration validated at startup; which indicators exist is code,
because an indicator has a definition and a definition is not a number. Invalid values fail the
context rather than falling back — unlike `ScoringContextProperties`, which clamps, because a clamped
lookback window still produces a defensible context where a negative weight produces a score silently
wrong in a direction nobody chose.

### The floor was in the wrong place, and it was too low

`training.evaluation.rules_baseline_scores` was a Python stand-in that said so in its own docstring.
Reimplementing the API's ruleset beside it would have been the mistake ADR-0010 §1 already rejects for
the account context, so it was **deleted rather than replaced**: `TrainingDatasetExporter` now
evaluates every example with `RuleEngine` — the same engine, on the same assembled request — and
`ruleScore` is a fourth field on each exported line beside `label`. The trainer reads the column.

**The stand-in was understating the floor.** On the same holdout it scored PR-AUC 0.1535 where the
shipped ruleset scores 0.2611, with precision 0.72 against 0.54 and a false-positive rate of 0.0021
against 0.0045. Every previously published margin over "the rules" was measured against something
weaker than the rules, by roughly 0.11 PR-AUC. The conclusion is unchanged — logistic regression
still scores 0.8327 and still clears ADR-0010 §5's 0.05 margin, now by 0.57 rather than 0.68 — and
`EVALUATION.md` records the correction rather than quietly carrying the new number.

**The rule score is a comparison column and never a feature.** It is not part of `ScoreRequest`, so
the extractor cannot see it; a model trained on it would be partly modelling the rules, and beating
them would then mean very little. An integration test asserts its absence from both request halves.

The baseline's operating point now also comes from the training rows, exactly as every candidate's
does. It previously came from a fresh computation over the same rows it reported on, which gave the
floor the one advantage the models are denied.

### Two defects the ruleset found by being run

- **`NEW_DEVICE` fired on an account with no history at all**, because "not one of the account's known
  devices" is trivially true when there are none. Fifteen points on the first transaction of every
  account quiet for a day is most low-activity accounts on most days. It now needs something to
  compare against. This is a deliberate difference from the model feature of the same name, which
  does report 1.0 on an empty history — the model sees `history_size` beside it and learns what the
  pair means, while a rule asserts a fixed weight with nothing beside it.
- **Reasons needed a tie-break.** `COUNTRY_CHANGE` and `NEW_DEVICE` both weigh 15, so ordering by
  contribution alone left their order to the evaluation sequence. A persisted assessment whose reason
  order moved between identical runs would be unreproducible for no reason at all.

### The stack was found in a crash loop, from the previous session

`docker compose ps` reported the API `Restarting (1)`. `SENTINELFLOW_SCORING_EXPORT_ENABLED` was
still set on the service from the last `make export-dataset`, and the export runner correctly refuses
to overwrite an existing dataset — so the container failed startup, restarted, and failed again.
Nothing else was affected and no data was lost.

The Makefile target does recreate the service without the flag afterwards; that second recreate
evidently did not take effect or was interrupted. **Check `docker compose ps` before assuming the
stack is healthy**, and if the API is looping, re-run the plain
`docker compose up -d --force-recreate --wait api`. Worth hardening the target itself when `make
replay` is written, since it will do the same dance.

### The scoring client and ADR-0011, merged as PR [#45](https://github.com/la3679/sentinelflow/pull/45)

**`ScoringClient` calls `/v1/score` inside ADR-0008 §3's budget** and returns one of three outcomes
that are deliberately not interchangeable: a score; unavailable, which the caller degrades from; and
rejected, which the caller dead-letters. Collapsing the last two into "scoring failed" is what the
class exists to prevent — one is a dependency being briefly down, the other is two services in one
repository disagreeing about a contract.

The circuit breaker counts **only** unavailability. A service answering 422 in a millisecond is not
sick, and opening on it would turn every later transaction into a degraded assessment and hide the
defect behind a system that still looks healthy. It is hand-written: one threshold, one timer, three
states, against a decision already made in an ADR that a library would make us express again in its
own terms.

**The budget is now a startup validation rather than a sentence.** Getting there needed the jitter
counted from the real schedule — `ceiling x retries` overstates the first two retries fivefold and
fails ADR-0008's own numbers at 11 s, where the true worst case is 9.6 s.
`FullJitterBackOff.worstCaseTotalDelay` computes it where the schedule is defined. `FullJitterBackOff`
itself moved to a new `resilience` package and became public: it now has two callers, and both are a
thread sleeping before it tries the same thing again.

**ADR-0011 decides the final score**: `max(rule, 0.6 x model + 0.4 x rule)` when scoring answered,
the rule score unchanged when it did not, banded from configured inclusive lower bounds validated at
startup.

### The ADR's first draft claimed a property its own formula does not have

It argued against `max(rule, model)` partly on the grounds that the formula rewards corroboration.
The test written from that sentence failed: with the floor, `combine(60, 60)` and `combine(60, 0)`
are both 60, so agreement adds nothing.

The formula was kept and the ADR was corrected. Counting agreement would be double-counting: the
model's features and the rules' indicators are computed from the same account context and are not
independent observations. **The rules set a floor and the model escalates above it** — a model can
raise a score and never lower one, which is a property an analyst can be told in one sentence. The
ADR now records the wrong claim, the failing test, and the defence, rather than presenting the
conclusion as though it had been obvious.

### `reason_codes` had been the wrong shape since Phase 2

`risk_assessments.reason_codes` was mapped as a list of bare strings while
`contracts/schemas/common.v1.json` and the API contract had always described an object with a code, a
description, a contribution and a source. Nothing had noticed because **nothing wrote the column** —
the first write would also have been the first time the two had to agree. `jsonb` is why it needed no
migration, and also why it sat unseen.

`ReasonCode.noIndicators()` came out of the same reading. The column's `CHECK` requires at least one
reason and its comment says an assessment with no reason cannot be defended to anyone — but a
transaction that trips nothing is the ordinary case, so something has to be said about it. "The
ruleset examined this and found nothing" is an explanation; an empty array is the absence of one.

### The assessment workflow, on `feat/assessment-workflow`

**`RiskAssessmentService` is the one method that runs the pieces in order.** Assemble the request,
evaluate the ruleset in process, call the scoring client, combine through `RiskPolicyProperties`,
band it, and write the row — with the transaction's move to `ASSESSED` and the `risk.assessed`
outbox row in the same database transaction as the ledger row that records the event as handled.
Three outcomes straight off ADR-0008 §2's table, and `ScoringRejectedException` is deliberately not
caught: absorbing a contract mismatch as a degraded assessment hides a defect behind a dashboard that
still looks healthy.

**Reasons are grouped by source rather than sorted as one list.** A rule weight of 10 and a log-odds
contribution of 1.2 are not comparable magnitudes, and interleaving them by size would rank them
against each other on the strength of a comparison that means nothing. Rules lead, because they sum
to the rule score and are the half an analyst can check. `risk-assessed.v1.json` now says so.

**`ScoringTransactionCreatedHandler` is a translation and nothing else.** It turns the service's
three outcomes into the two answers a Kafka consumer understands, and it lives in the messaging
boundary rather than the service because "dead letter" is a delivery concept — Phase 5's rescoring
endpoint will call the same method from an HTTP request that has no partition to block.

### Two contract corrections the first write forced

**A model contribution is not points added to the final score.** `common.v1.json` described
`reasonCode.contribution` as exactly that and bounded it to ±100. True of a rule; false of the model,
whose contribution is `coefficient x standardised value` on the log-odds scale before calibration.
The bound went with the description rather than being kept as a safety net: a log-odds contribution
has no natural ceiling, so honouring a bound means clamping, and clamping changes a number an analyst
reads without saying so.

**The rule score had no version to be defended by.** V4 gave `risk_assessments` a model version, a
feature version and a policy version and no column for the ruleset — while `RuleOutcome`'s Javadoc,
the export manifest and the configuration comment all said the ruleset version is persisted on the
assessment. Unnoticed for the same reason the `reason_codes` shape was: nothing had ever written the
table. V8 adds it NOT NULL with no default and no backfill, and refuses loudly if it ever finds a
row.

### Registering the first handler broke a delivery test, which is the useful part

`TransactionCreatedConsumerIT` asserted that a successfully handled transaction stays `PENDING`, and
that stopped being true because scoring correctly moves it to `ASSESSED`. The suite's subject is
delivery, so the scoring handler is replaced there with a no-op rather than the assertion being
rewritten to describe the risk workflow — exactly the coupling the port exists to prevent, showing up
the first time it could.

### Three defects that only running the compose stack could find

Every suite in the repository was green through all three. They are recorded at length under "Next
three actions", and the short form is:

- **Nothing created the Kafka topics**, though ADR-0006 §3 decided they are created explicitly and
  disabled auto-creation. Seven healthy services and no publishable message.
- **The scoring client negotiated HTTP/2 against uvicorn**, so every request was refused: 13,455
  degraded assessments and 6,224 dead letters before it was traced.
- **Two PowerShell targets had never worked at all.**

The generalisable part is that Testcontainers and the compose stack are not the same system —
Testcontainers auto-creates topics, and `com.sun.net.httpserver` ignores an upgrade attempt uvicorn
refuses — and only one of the two is what a demo runs on.

### What remains in Phase 4

| Piece                                       | State                                                           |
| ------------------------------------------- | --------------------------------------------------------------- |
| ADR-0008, the scoring boundary              | **done** (#29)                                                  |
| Scenario generator, `make seed`             | **done** (#30)                                                  |
| Scoring contract                            | **done** (#31)                                                  |
| Versioned feature pipeline                  | **done** (#34)                                                  |
| ADR-0010, model and evaluation choice       | **done**                                                        |
| Account-context assembler                   | **done** (#37) — shared by training and serving                 |
| Labelled dataset export                     | **done** (#39) — `make export-dataset`, never persisted         |
| Transparent rules baseline                  | **done** (#44) — `apps/api`, and the export carries its verdict |
| Reproducible training, evaluation, registry | **done** (#41) — `make train`                                   |
| Model card and `EVALUATION.md`              | **done** (#41) — the card is generated, never hand-written      |
| `/v1/score` and `/v1/model` implementations | **done** (#43) — served from the registry entry                 |
| Spring scoring client with resilience       | **done** (#45) — timeouts, retry, breaker; not yet called       |
| ADR-0011, the final score and the bands     | **done** (#45) — `RiskPolicyProperties`, validated at startup   |
| The assessment workflow that joins them     | **done** — service, handler, persisted assessments              |
| Off-hours generator defect                  | **fixed** (#38) — found while building the export               |
| `make replay`                               | **done** — the two operational scenarios, both paths            |

**`make replay` is implemented, and narrower than the name suggests.** It replays the two
operational scenarios §8.3 lists that nothing else produces — a temporary scoring-service outage and
a malformed event reaching the dead-letter path. The transaction shapes are `make seed`'s, and the
HTTP replay endpoint §10 lists is API surface that waits for the authorization and rate limiting of
later phases. Both the bash script and the PowerShell runner were run end to end on 2026-08-27.

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

## Completed — Phase 5, merged as PRs [#49](https://github.com/la3679/sentinelflow/pull/49) through [#52](https://github.com/la3679/sentinelflow/pull/52)

**Three pull requests merged**: alert creation as
[#49](https://github.com/la3679/sentinelflow/pull/49), the investigation state machine with
ADR-0012's authentication as [#50](https://github.com/la3679/sentinelflow/pull/50), and assignment,
notes, feedback and the queue reads as [#51](https://github.com/la3679/sentinelflow/pull/51).

**All four pull requests are merged**, the reporting endpoints last as
[#52](https://github.com/la3679/sentinelflow/pull/52) — see the section below for what its four red
tests turned out to be, and "Acceptance criteria status — Phase 5 gate" for the evidence each
criterion rests on.

**The alerting rule joined the policy object** rather than starting a second one. ADR-0008 §4 gives
this service "the alerting policy applied to a final score at runtime", and deciding which bands are
worth reviewing is that policy. `RiskPolicyProperties` gained `alertFromBand` — monotone in severity
by construction, so an alerting rule that skipped a band is not expressible — and `priorityByBand`,
which is separate because the band describes the score and the priority describes the queue. Both
halves of its validation are refusals at startup. `policy.version` moved to **1.1.0**, because what
that version describes changed.

**V9 adds `alert_reference_seq`**, the same shape as V7's. Four digits caps it at 9,999 and, unlike
the transaction reference, that is a ceiling this project can plausibly reach. Left as a loud
failure rather than widened: `NO CYCLE` means exhaustion arrives as a refused INSERT naming the
sequence, which is a legible signal that the alerting policy is producing more alerts than any
review capacity could absorb.

**`AlertRaiser` writes three rows in the assessment's own transaction** — the alert, its first
`alert_actions` row attributed to the system principal, and the `alert.created` outbox row keyed by
the alert's identifier (ADR-0006 §3). The summary is built from the band, the score, the transaction
reference and the leading reason **code**, never a reason's generated description: a description
legitimately names a device handle or an amount ratio, which is right on a detail page an analyst has
opened and wrong on a queue row and in an event that leaves this service.

**The flag and the alert come from one decision.** `alert_raised` is written where the band is
computed, inside `RiskAssessmentService`'s two pure methods, and the raiser acts on it. Asking the
policy a second time would be two answers to one question, and the day they disagreed a row would
claim an alert nobody could find.

### The model alone cannot raise an alert — now a stated policy rather than an accident

ADR-0011's combination is `max(rule, 0.6 x model + 0.4 x rule)`. With a rule score of zero the best a
perfect model can produce is **60**, and the alerting band starts at 70 — so **a transaction that
trips no transparent indicator can never open an alert, however confident the model is.** The
smallest rule score that lets a maximal model reach the band is 25, which is exactly one rule firing.

This is a consequence of two decisions that were each defensible alone: ADR-0011 §1's floor, and
`alertFromBand: HIGH`. It was not visible until they met, and it is not obviously wrong — "we only
alert when at least one indicator an analyst can read has fired" is a defensible policy for an
explainability-first console, and it is arguably the point of the floor. But it has to be a stated
decision rather than an accident, and right now it is an accident.

**Resolved by stating it, not by moving a number.** It is now **ADR-0011 §4**, with the arithmetic
written out, the reason it is wanted, and the cost of wanting it. The reason: the console is
explainability-first, so every alert it raises can be opened on an indicator an analyst can check and
dispute, and an alert justified only by "the model was confident" is the one they learn to clear
without reading. The cost, stated in the same place: a shape the model recognises and the ruleset
misses is scored, banded, persisted and visible, and opens nothing.

Changing the formula or the threshold instead would have been re-deciding an ADR without the measured
alert volume that ADR already asks these numbers to be revisited against. Two tests in
`RiskPolicyPropertiesTests` now pin both halves — a maximal model over a silent ruleset bands MEDIUM,
and 25 is the rule score where the combination first clears 70 — so moving either number cannot alter
the implication silently. The ADR's "revisit if" gained the condition that would reopen it, and the
route back is a new rule that makes the shape transparent rather than an alert nobody can review.

### What is done, and what is not

| Piece                                          | State                                                            |
| ---------------------------------------------- | ---------------------------------------------------------------- |
| Alerting policy on `RiskPolicyProperties`      | **done** — 21 unit tests                                         |
| `alert_reference_seq` (V9)                     | **done** — `MigrationIT` asserts it applied                      |
| `AlertCreatedPayload` and its contract test    | **done** — 4 assertions, including the `NEW` const               |
| `AlertRaiser`, and the wiring into the service | **done** — five cases in `RiskAssessmentWorkflowIT` exercise it  |
| An integration test for the alert path         | **done** — the alert, its summary, its history row, its event    |
| "The model alone cannot alert" (ADR-0011 §4)   | **decided and recorded** — stated as policy, asserted by 2 tests |
| The investigation state machine                | **done** — 9 property and edge tests                             |
| Transitions, audited and version-checked       | **done** — 8 cases against PostgreSQL                            |
| Operator authentication (ADR-0012, V10)        | **done** — 7 unit, 7 integration over real HTTP                  |
| The transition endpoint and role authorization | **done** — 9 cases, including both role refusals                 |
| Assignment, notes, analyst feedback            | **done** — 16 and 11 cases over real HTTP                        |
| The queue and one-alert reads                  | **done** — the ordering asserted term by term                    |
| The CSV escaping (`CsvWriter`)                 | **done** — 17 unit tests, every formula-leading character        |
| Reporting endpoints                            | **done** — 10 cases over real HTTP, both reports                 |
| The reports in `contracts/openapi/`            | **done** — two paths, the summary schema, the export's 413       |
| Phase 5 closed against its gate                | **done** — four criteria, each evidenced by a named test         |

### The reporting branch, and what the four red tests actually were

**`feat/alert-reporting` merged as [#52](https://github.com/la3679/sentinelflow/pull/52).** What was
on it:

- **`CsvWriter` is the part that matters** — 17 unit tests covering every character a spreadsheet
  treats as a formula (`=`, `+`, `-`, `@`, tab, carriage return), the apostrophe prefix going
  _inside_ the RFC 4180 quoting rather than outside it, and the deliberate cost that a negative
  number reads as text. Not theoretical: the alert summary is generated text built from a transaction
  reference that arrived through the ingestion endpoint ADR-0012 §5 leaves open until Phase 8.
- **`GET /reports/alert-summary`** — counts by status, priority and band over a half-open window,
  every key present including the zeroes. Not paged, because its size does not depend on the data.
- **`GET /reports/alerts.csv`** — capped at 10,000 rows and refused with `413` above it, rather than
  paged: a report somebody opens in a spreadsheet is a file rather than a cursor.
- **Both paths in `contracts/openapi/sentinelflow-api.yaml`** — the shared window parameters, the
  `AlertSummary` schema, and `ExportTooLargeProblem`, which carries both `rows` and `limit` so a
  client can narrow the window arithmetically rather than by halving it until it works.

**The failure was in the test fixture, and the diagnosis held.** `AlertReportIT` derived its window
from a class constant, so every test in the class wrote into the same hour and the four that assert
an exact total counted rows the tests before them had left. The two that assert no total — the
zero-key case and the formula-escaping case — passed throughout, which was the evidence that the
endpoints themselves behaved. They did: **no production code changed to make the four pass.**

**The fix is a window per test**, drawn from a per-run epoch by an `AtomicInteger` in `@BeforeEach`.
The one thing worth keeping from doing it: **the windows are two hours apart rather than adjacent.**
`theWindowIsHalfOpen` deliberately reads the window immediately after its own, to prove a row on the
boundary is counted once rather than twice or never — with a one-hour stride that read would have
landed in the next test's window, and the fix for a fixture that leaks between tests would have
leaked between tests.

### Three decisions in the alert operations worth keeping

**A note takes no version and publishes no event.** It is appended rather than replacing anything,
so two analysts writing one at the same time both succeed — demanding `expectedVersion` would refuse
the second for no reason a user could act on. And `alert.updated` has no field for the text, so an
event could only announce that a note exists; an analyst's own words about a transaction belong on a
detail page somebody opened rather than on a topic that leaves this service. The same rule
`AlertRaiser` follows when it builds a summary from a reason code rather than a description.

**Feedback is recorded against the assessment and cites the alert.** Rescoring writes a new
assessment rather than editing one, so a label attached to the alert would silently follow a
decision it was never given about. One analyst has one verdict per assessment and revising it
replaces the label, because two opposite labels from one person about one decision cannot both be
training data.

**The queue's ordering is not the caller's to choose.** Open work before closed, then priority, then
oldest first. A `sort` parameter would let a console quietly change which alerts an analyst sees
first, which is an operational decision rather than a display one.

### The state machine, and the three things it is worth knowing about

**Which moves exist is not configuration.** The band thresholds are numbers on their own schedule
and live in `application.yaml`; this is a definition of what an investigation _is_. A stack
configured to allow `CLOSED → NEW` would reopen closed alerts and produce an audit trail no other
stack could reproduce.

**Terminal means terminal, and that is load-bearing.** `Alert.transitionTo` clears `closed_at` when
it moves to a live state, because `alerts_closed_at_consistent` requires a live alert not to carry
one — so a legal move out of a terminal state would erase when the investigation ended, which is the
timestamp every resolution-time figure is computed from. Reopening is therefore not a transition at
all; it would be a new alert citing the same assessment, and it does not exist.

**Three property tests carry more weight than the edges**: no self-transitions, which the
`alert_actions` CHECK would refuse at commit; no outgoing move from a terminal state; and a
breadth-first search proving no live state can be stranded without a path to a terminal one. Each
catches a change that would satisfy every edge test and still break something.

### The concurrency check is made twice, and neither half is redundant

`expectedVersion` is compared against the loaded alert, and the persistence provider compares it
again at flush. The explicit check is for a caller working from a stale read — the analyst who
opened the alert five minutes ago — and it fails before anything is written and can name the version
the alert is actually at. It is a read followed by a write, so nothing about it is atomic;
`@Version` on the UPDATE is what makes the loser of a genuine race lose. Both surface as the same
409, because from the caller's side they are the same thing.

The flush is not tidiness either: `alert-updated.v1.json` requires the version **after** the change,
and that value does not exist until the UPDATE is written.

### Authentication, and the gap it deliberately leaves

ADR-0012. A username and password are exchanged for a thirty-minute bearer token; V10 adds
`user_credentials`, and the system principal deliberately has no row in it — which is what makes
authenticating as the principal that attributes automated actions impossible rather than merely
forbidden. Demo operators are created by the application seed from a password `make bootstrap`
generates, never by a migration: a hash committed to V10 would be a credential in the repository and
the same one on every machine that ran it.

**`POST /api/v1/transactions` stays unauthenticated**, deliberately and temporarily. It is a
machine-to-machine surface whose caller is a payment pipeline rather than a person, so an operator's
password buys nothing there; it needs its own credential together with the rate limits and payload
bounds that belong beside it, which is Phase 8's. Recorded in ADR-0012 §5, in the README's stated
limitations, and under known issues below.

### Two defects the endpoint found by being run

- **An auditor's token produced a 500 rather than a 403.** `@PreAuthorize` throws inside the
  handler, so the dispatcher sees `AccessDeniedException` before Spring Security's
  `ExceptionTranslationFilter` can — and it fell through to the catch-all. `ApiExceptionHandler` now
  maps it to the same body the filter chain writes, so a client cannot tell which layer refused it.
- **The security configuration broke every schema test.** A `SecurityFilterChain` needs
  `HttpSecurity`, which does not exist in a context started with `webEnvironment = NONE`. The
  cryptographic beans — the password encoder, the encoder and the decoder — are needed by the seed
  and the login service and have nothing to do with HTTP, so they are declared separately from the
  filter chain, which is now `@ConditionalOnWebApplication`.

### A fixture that passed alone and failed behind another suite

`OperatorAuthenticationIT` first seeded its operators. One container serves the whole fork and
`DeterministicSeedLoader` skips a database that already has parties in it, so the suite passed on its
own and failed whenever anything had written a customer first. It now creates its own operators; that
the seed gives its own working credentials is asserted where the seed is.

### What the alert-path test needed, and why it is worth reading

**No scoring stub can provoke an alert on its own**, which is ADR-0011 §4 arriving as a practical
obstacle in the first test that needed one. The fixture builds an account history the ruleset reacts
to: four transactions inside five minutes fire `VELOCITY_5M_HIGH` for 25, a fifth originating
elsewhere adds `COUNTRY_CHANGE` for 15, and 40 against the stub's model score of 92.5 combines to
71.5 — HIGH, at HIGH priority.

**The instants are fixed rather than `now()`.** Two of the seven rules read the clock: the velocity
window is five minutes wide, and `OFF_HOURS` fires between 02:00 and 04:59 UTC. A history built from
`now()` would have scored 40 by day and 50 overnight, so a suite asserting 71.50 would have failed
only on builds that ran in the small hours. `SchemaFixtures.insertTransactionFrom` takes the instant
for that reason, and now has its first caller.

**One merchant across the whole history, deliberately.** A fresh merchant per row would fire
`DISTINCT_MERCHANTS_1H_HIGH` as well, and the test states its arithmetic exactly rather than
asserting a band and hoping.

### A correction to this file

An earlier entry said `alerts.top_reason_code` "is a string on the entity while
`contracts/schemas/alert-created.v1.json` describes an object", and listed settling it as Phase 5
work. **That was wrong.** There is no such column and no such field: `topReasonCode` exists only on
the event, and the schema has always described it as a `reasonCode` object. Nothing needed settling —
the payload derives it from the assessment's reason codes at publication time, which is what it now
does. Trusting the repository over this file, as `CLAUDE.md` says to.

The schema's description of that field was corrected instead, for a different reason: it called it
"the single largest contributor", which is not well defined across two incomparable scales.

## Completed — Phase 7, merged as PRs [#70](https://github.com/la3679/sentinelflow/pull/70) through [#73](https://github.com/la3679/sentinelflow/pull/73), [#82](https://github.com/la3679/sentinelflow/pull/82) and [#83](https://github.com/la3679/sentinelflow/pull/83)

**All six deliverables are on `main`, and the gate is claimed with evidence for all four criteria.**
[ADR-0016](docs/adr/0016-observability-signals-and-their-boundaries.md) fixes what each signal answers
and what none of them may carry; the metric set, structured logging with redaction, W3C trace
propagation, five Grafana dashboards, two resilience drills and nine runbooks with thirteen alerting
rules are merged. The gate table below says what each piece of evidence covers **and what it does
not**, which is the part a claimed gate most needs.

### The metric set (PR [#70](https://github.com/la3679/sentinelflow/pull/70))

Eleven application metrics became a set that answers the four questions the deleted console panels
named. The scoring client counts every call by outcome and times the three that made one;
`breaker_open` is counted and deliberately **not** timed, because a refused call takes no measurable
time and folding those zeroes into the histogram would make an outage look like the fastest scoring
the system has ever done. The breaker is three gauges, one per state, rather than one gauge with an
encoding every panel would have to carry in a comment.

Ingestion counts all three outcomes, so a retry storm reads as a replay rate rather than as
throughput that looks flat while the database works.

**Consumer lag and dead-letter depth are measured by the API with a Kafka admin client**, weighed in
ADR-0016 §6 against a `kafka-exporter` container. A broker that stops answering **holds the last
reading** rather than reporting zero — zero is what a healthy pipeline reports, so an alert rule
would say everything was fine throughout an outage.

Buckets everywhere, never client-side percentiles (ADR-0016 §3).

### Structured logging and redaction (PR [#71](https://github.com/la3679/sentinelflow/pull/71))

`structlog` had been a declared dependency of the scoring service since Phase 4 with every log call
going through it — and **nothing ever called `structlog.configure`**, so it rendered with console
defaults. The API ran Logback's default pattern encoder. Both now emit JSON in their containers and
human-readable text on a terminal.

The redaction rule is that a log line is built from named fields at the call site. Eight records that
would have printed an amount, a device handle or an idempotency key through a generated `toString`
now print identifiers and classification and say what they withheld. Account and merchant references
stay: they are how an operator finds the thing they were paged about.

Two findings recorded in the code rather than worked around:

- **Spring's own `RestClient` logs the body it is about to send at `DEBUG`**, so the first version of
  `LogRedactionIT` was catching its own harness. It posts with the JDK's client now.
- **`StreamHandler` binds its stream at construction and pytest swaps its capture buffer between
  phases**, so three redaction assertions passed against an empty string until that was understood —
  the worst possible way for a redaction test to be green.

### Trace propagation (PR [#72](https://github.com/la3679/sentinelflow/pull/72))

V6 left `outbox_events.trace_id` nullable and `TransactionWriter` said why it was null. It is
populated now, and `V11` adds the `trace_parent` the outbox needs to carry a trace across its own
delay — continuing a trace needs the parent span id, which a trace id alone does not have. A check
constraint requires the two columns to agree.

The listener continues the trace from the record header. **Producer-side observation stays off
deliberately**: the publisher writes the header itself from the stored value, and template
observation would overwrite it with the scheduler thread's context, reintroducing the second
disconnected trace the whole arrangement exists to prevent.

**Four defects, and only one was catchable by a test:**

1. **`micrometer-tracing-bridge-otel` alone produced no `Tracer` bean at all.** Boot 4 moved the
   autoconfiguration into its own module, exactly as it did with Flyway. Three tests were green while
   the feature did nothing; an integration test looking at a database row found it.
2. **`management.otlp.tracing.*` is the pre-4.1 spelling and binds nothing.** Those keys are still in
   the configuration metadata with no description and no default. Propagation worked perfectly the
   whole time, so every assertion passed while Tempo had never received a span. The names that work
   are `management.opentelemetry.tracing.export.otlp.endpoint` and
   `management.tracing.export.otlp.enabled`.
3. **The OpenTelemetry starter auto-configured a second metrics registry** pointed at
   `localhost:4318`, failing with a stack trace on a timer. Off now: metrics are Prometheus's, and
   two registries publishing the same meters is two places the same rate can disagree.
4. **Tempo and the collector cannot be healthchecked at all** — both images are distroless, and the
   probe failed with `stat /bin/sh: no such file or directory` while Tempo ran perfectly. Neither has
   one now, and both say why.

### The dashboards, and the five defects they found (PR [#73](https://github.com/la3679/sentinelflow/pull/73))

Five dashboards, provisioned from files with `allowUiUpdates: false` so a dashboard tuned during an
incident cannot be the only copy.

Building them was the best test the metric set has had. **Every panel query was run against the live
Prometheus and eight came back empty**, of which five were application defects: the alert counters
did not exist until an alert was raised, ingestion conflicts likewise, consumer outcomes vanished on
every restart, and the outbox publication timer had no buckets at all — so a p95 over it was not
merely wrong but impossible. The fifth was a query of this session's own naming `CONFIRMED_FRAUD` and
`FALSE_POSITIVE`, which are not statuses this system has.

The first three are one defect with one fix, and it is a decision this repository had already made:
`apps/scoring/serving/metrics.py` has initialised its counter series since Phase 4. It had simply
never been applied anywhere else.

### The two drills (PR [#82](https://github.com/la3679/sentinelflow/pull/82))

**Both are tests, not documents.** The gate asks that a documented failure drill succeeds, and a
script somebody runs by hand succeeds until the day nobody runs it. Testcontainers can fail a
dependency inside a test, so both failures the system is designed to survive are now exercised on
every `make test-integration` and on every CI run.

`ScoringOutageDrillIT` posts thirty transactions through the whole path — HTTP ingestion, outbox,
real broker, real listener, real ruleset — while the scoring service answers 503. It asserts what
`ScoringClientTests` cannot, because the claim is about the system rather than about a client: every
one of the thirty was assessed, every assessment was marked degraded and stated **no** model score
rather than a zero, none was dead-lettered, and every transaction reached `ASSESSED`.

**The number worth keeping is the last assertion.** The breaker keeps its shipped threshold of five,
and the thirty transactions cost **five records' worth of HTTP attempts rather than thirty** — the
other twenty-five degraded without a call being attempted, counted by
`sentinelflow_scoring_calls_total{outcome="breaker_open"}`. That is ADR-0008 §3's "the part that
matters at scale", measured rather than argued. Recovery needed nothing restarted: the open window
elapsed, the next transaction was let through as the probe, it succeeded, and the pipeline scored
again.

`BrokerOutageDrillIT` freezes the broker mid-run and asserts the whole of ADR-0005's claim in one
sequence: ingestion kept answering `202`, because the write path ends at a table; the events sat
`PENDING` with `attempt_count` climbing and `last_error` recorded; `sentinelflow_outbox_pending` and
`sentinelflow_outbox_oldest_age_seconds` reported the backlog an operator would be watching; nothing
reached `FAILED`; and on recovery the relay's next poll found the same rows still due and drained
them, leaving exactly one `processed_events` row and one assessment per event.

#### Two things the drills forced, and one they cannot claim

**A Testcontainers `stop()` is not a restart.** It removes the container, and the `start()` after it
creates a new one on a new ephemeral host port with an empty log — a different broker, at an address
nothing in the context is configured for. Going under Testcontainers to `docker stop` and
`docker start` does not help either: Docker re-picks the ephemeral host port on each start. That was
checked with a throwaway container rather than assumed — `32768` before, `32769` after.
`docker pause` touches neither the port mapping nor the log, so the broker that comes back is the one
that went away, and `KafkaContainerSupport` gained `pauseBroker`/`resumeBroker`/`isBrokerPaused`.

**A drill that leaves the broker frozen fails every suite after it.** There is one broker per JVM
fork. The drill resumes in `@AfterEach` and again in `@AfterAll`, and the evidence that it works is
that `RiskAssessmentWorkflowIT` — which runs immediately after it — is green in the same run.

**What the broker drill does not prove:** survival of a connection _refusal_. A frozen container's
kernel still completes handshakes into the accept backlog, so a client sees a request that never gets
an answer rather than an immediate rejection. Both end in the producer's delivery timeout expiring and
`KafkaEventPublisher` throwing, which is why the drill is honest about which one it exercised.

### The nine runbooks, and the rules that point at them (PR [#82](https://github.com/la3679/sentinelflow/pull/82))

Five were missing and four predated the signals they describe. `docs/operations/RUNBOOKS.md` now has
all nine the plan asks for, every one naming metrics that exist and dashboard panels that are
provisioned. Runbooks 3 and 4 carry a "What a drill actually showed" section, so the two failures the
drills exercise are described from observation rather than from reasoning.

`infra/prometheus/rules/sentinelflow.yml` holds thirteen rules, each annotated with the runbook
section that answers it. **Nothing pages anybody** — there is no Alertmanager in this stack, and
adding one would be a service with no recipient — so a firing rule appears on Prometheus's own Alerts
page. The rules are worth having because they put the thresholds somewhere they can be read and
argued with. Most are derived from a configured budget or interval and name it; two are conventions
and say so.

#### Three corrections the runbooks forced

Reprocessing a dead-lettered event, reviving a `FAILED` outbox row and rescoring a degraded
assessment were each described as "Phase 5 work". **Phase 5 has shipped and its deliverable list
never contained any of them**, and nothing in `docs/planning/IMPLEMENTATION_PLAN.md` allocates them
now. The runbooks say that plainly and give the manual procedure, which is what actually exists.
ADR-0005 §5 still fixes what each would have to be when it is built: administrator-only and audited.

#### One finding recorded rather than fixed

**There is no index on `alerts (created_at)` alone.** The three that exist lead with `status`,
`assignee_id` and `transaction_id`, so a report window is a sequential scan over `alerts` plus a
sort. At this demo's volumes that is invisible; over a large window on a large table it is the whole
cost. Runbook 8's diagnostics record it. Not fixed there: a measured optimisation is Phase 9's, and
an index added with no before-and-after is a change nobody can justify afterwards.

### Redaction, widened until it matched what the ADR claims (PR [#83](https://github.com/la3679/sentinelflow/pull/83))

ADR-0016 §4 says the redaction test captures "every line the service emits at every level including
`DEBUG`". `LogRedactionIT` ran the application's own package and Spring Web at `DEBUG` and left the
rest of the framework at its shipped level, and it drove only the ingestion path. That is narrower
than the claim, and the previous gate table said so rather than calling the criterion met.

It now runs at **`logging.level.root=DEBUG` and pins nothing itself**, so what protects the
assertions is `application.yaml`'s own configuration and not something that exists only under test.
It drives five paths instead of one: ingestion, the transaction read path, the alert workflow,
reporting, and signing in — carrying an amount, a device handle, an idempotency key, a real password
and a real bearer token.

**Four leaks, all of them real, none visible before:**

1. **Hibernate dumps every entity in the persistence context at `DEBUG`**, one line each, every
   property rendered by its type. On this schema that carried a transaction's amount, its device
   handle and its idempotency key; an outbox row's whole event payload; and an alert action's note.
   An entity cannot defend itself — the printer reads properties through the persister rather than
   calling `toString` — so pinning `org.hibernate.orm.core` in `application.yaml` is the only control
   available. `org.hibernate.orm.jdbc.bind` is pinned beside it for the same reason, and
   `org.hibernate.orm.jdbc.error` was already there as the precedent.
2. **`AlertNoteRequest` printed the note.** Spring logs `Read "application/json" to […]` at `DEBUG`,
   rendering the deserialised record, and ADR-0016 §4 forbids a whole request body at every level.
   Fixed by the ADR's own first mechanism — a redacting `toString` — on the note, transition,
   assignment and feedback requests.
3. **`TransactionResponse` was safe by accident.** Spring's response-side line truncates before
   reaching the amount, which is the field's _position_ in a generated `toString` acting as a
   control. Given a redacting `toString` so it is safe by construction.
4. The bearer token and the password never appeared, which is the half of the result worth stating
   too: two of the five planted values needed no fix.

The scoring service's suite gained the third outcome it was missing — the `unavailable` path, over an
empty registry — so all three of its own metric labels are now driven by a redaction test.

**What this still does not cover:** the drills' own contexts run at the shipped log level, so a leak
that only occurs during a degraded assessment or a publication failure would not be caught here. And
`org.hibernate.orm.core` is pinned rather than made safe; an operator who lifts that pin gets entity
dumps, which is the documented trade rather than a control.

### Evidence, from runs that happened

| Claim                         | Evidence                                                                                                                                                                        |
| ----------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| API unit tests                | 232 tests, 0 failures — `./mvnw -B verify -DskipITs -Djacoco.skip=true`, 2026-08-30                                                                                             |
| Full Testcontainers suite     | 303 tests, 0 failures on the tracing branch after `MigrationIT` was updated for V11                                                                                             |
| Scoring suite                 | 186 passed, `ruff` and `mypy --strict` clean over 46 source files                                                                                                               |
| **One trace, end to end**     | Trace `c591f5172d73068c9f55902c1777d29d`: seven spans, `http post /api/v1/transactions` at the root, `transaction.created.v1 process` beneath it, the scoring call beneath that |
| **Dashboards load with data** | **48 panel queries, 0 empty, 0 malformed**, against an API restarted less than a minute earlier                                                                                 |
| Spans reaching the backend    | `tempo_distributor_spans_received_total` 148                                                                                                                                    |
| Cross-service log join        | The scoring service's line for that transaction carries the same `trace_id`, `correlation_id` and `transaction_id`                                                              |
| CI                            | All ten required checks green on #70, #71, #72 and #73                                                                                                                          |

## Completed — Phase 6

**Eight pull requests merged, nothing open**: the API migration audit as
[#56](https://github.com/la3679/sentinelflow/pull/56), the alert's legal targets as
[#57](https://github.com/la3679/sentinelflow/pull/57), a state checkpoint as
[#58](https://github.com/la3679/sentinelflow/pull/58), the transport and authentication as
[#59](https://github.com/la3679/sentinelflow/pull/59), the transaction read endpoints as
[#62](https://github.com/la3679/sentinelflow/pull/62), the typed domain with the alert and
transaction screens as [#63](https://github.com/la3679/sentinelflow/pull/63), the last four screens
with `src/mocks/` deleted as [#65](https://github.com/la3679/sentinelflow/pull/65), and the
live-update decision with the two console defects it found as
[#67](https://github.com/la3679/sentinelflow/pull/67).

### Live updates are polling, and a stream has three preconditions

[ADR-0015](docs/adr/0015-live-updates-polling-and-server-sent-events.md) decides the phase's last
deliverable, and it decides two things rather than the one the build prompt asks about. §8.6 picks
SSE over WebSockets for the dashboard; it does not say whether this phase should stream at all, and
that is the larger half.

**It should not, because there is nothing to stream.** `compose.yaml` runs no generator, and
transactions arrive from `make seed` as a batch, from `make replay` four at a time, and from a
hand-written post. Between the bursts there is no event stream for a socket to hold open.

**When one is built it is SSE, read with `fetch` rather than `EventSource`.** One-directional
traffic, inside ADR-0013's CORS model and the bearer header, with reconnection in the protocol.
`EventSource` cannot set headers, and the two ways round that — a token in the query string and a
cookie — are forbidden by `CLAUDE.md` and rejected by ADR-0012 §1 respectively. A stream must also
not outlive the token that authorized it.

**Three preconditions gate building it**, so a later session can tell "not yet" from "forgotten": a
continuous producer, a fan-out that survives a second API instance, and Phase 7's metrics. The
second is the one worth remembering — an in-memory emitter registry is correct for exactly one
instance, and with two the `transaction-risk` group splits its partitions and a browser silently
misses every event the other instance consumed.

**Writing it found two defects in the console.** The alert queue had no refresh at all: `listAlerts`
is invalidated only by mutations the same browser makes, so an alert the pipeline raised or a
transition another analyst made stayed invisible until the page was navigated away from and back.
And `setupListeners` had been called since the store was written with no endpoint opting into either
event, so the wiring had never caused a single request. Both fixed, and both new tests fail with the
flags off — which is how that was confirmed rather than assumed.

### The four screens the console invented are decided, and none went the same way

[ADR-0014](docs/adr/0014-where-the-console-s-remaining-screens-get-their-data.md) decides all four
and [#65](https://github.com/la3679/sentinelflow/pull/65) built them. Deciding them one at a time
was the point: they went four different ways.

- **`GET /models/active` is composed by the API** from the scoring service's `/v1/model` and its own
  `RiskPolicyProperties`. The contract had it at Phase 4 with no handler, and building it over
  `model_registry` would have answered a permanent 404 — that table has never had a row written to
  it, and the registry of record is the one the scoring service loads from disk. **A scoring outage
  does not blank the screen**: the policy half is always knowable, and the bands and thresholds are
  exactly what somebody would be looking for during one.
- **`GET /system/health` is a small new endpoint** covering what the API can observe: itself,
  PostgreSQL through the pool, and the scoring service with the breaker's state. Always 200,
  because "the scoring service is down" is the answer rather than a failure to produce one — an
  endpoint that returned an error status when a dependency is sick could not be told apart from one
  that is sick itself.
- **The overview is composed in the client**, from `GET /reports/alert-summary` and `GET /alerts`.
  An aggregate endpoint would have been a second implementation of risk-band counting beside the
  report that already does it. Two requests and a screen that can be half-loaded is the cost, and
  both are handled.
- **The reports screen uses the endpoints that already existed**, including
  `GET /reports/alerts.csv`, which had been tested and unused since Phase 5 and now has its first
  client.

**Three panels of invented numbers were deleted rather than sourced.** Throughput per hour, scoring
latency percentiles, and consumer lag with dead-letter depth. Nothing measured any of them and three
of the four are Prometheus's and Kafka's rather than this API's. The screens name Phase 7 instead.

**`model_registry` stays empty and unread, recorded as debt.** The day something needs to write to
it, that is a decision about where the model registry of record lives — with a promotion path, an
audit trail and a rollback story — and it supersedes ADR-0014 §1 rather than extending it.

### Three endpoints the contract described and nobody had built

`GET /transactions`, `GET /transactions/{id}` and `GET /transactions/{id}/assessment` have been in
`contracts/openapi/` since Phase 3. **`TransactionController` implemented only the `POST`.** Nothing
had noticed because nothing had called them: the console rendered transactions from fixtures, and
the audit that opened Phase 6 says it checked the handlers and on these three checked the contract.

Built in [#62](https://github.com/la3679/sentinelflow/pull/62) with 16 integration tests. Three
decisions in it worth knowing:

- **The response publishes references, and the entity holds none.** `TransactionRecord` carries the
  account and the merchant as bare UUIDs; the contract publishes `ACC-000123` and `MER-0042`. The
  page is a projection joined in the database, because resolving two references per row afterwards
  is an N+1 over a page of up to 200.
- **The band comes from the highest-version assessment**, through a `LEFT JOIN` that names the
  version. A plain join would page a rescored transaction once per assessment; an inner join would
  hide every transaction that has not been scored yet, which is a normal state and exactly the one
  an operator looks for.
- **Two distinct 404s.** "No such transaction" is the caller's mistake; "not scored yet" is this
  system still working. Same status because a client polling acts identically either way, different
  problem type so the log and the body need not be as vague as the status code.

**One defect worth keeping.** PostgreSQL refused the paged query at prepare time — `could not
determine data type of parameter $5` — on a bare `:occurredAfter IS NULL`. Valid HQL and valid JPQL;
a placeholder in that position has no context to be typed from, while the enum and string filters
beside it need no cast because Hibernate binds those with a declared type. The two
`cast(... as Instant)` calls are load-bearing, and removing either returns every list request to a
500 that no test of the query's _logic_ would catch.

**A fourth is still missing.** `GET /models/active` is in the contract at Phase 4 and has no
handler either. `model_registry` exists as a table, has constraint tests, and **nothing has ever
written a row to it** — the real registry is the one the scoring service serves from disk at
`GET /v1/model`. That is piece 4's problem, and it is a decision rather than a gap to fill.

### The console reads the API for every alert and every transaction

[#63](https://github.com/la3679/sentinelflow/pull/63). `domain/types.ts` is rewritten against the
contract, and the four screens that read it read the API.

- **`ALLOWED_TRANSITIONS` is deleted rather than corrected**, and the controls render from the
  alert's own `legalTargets`. A corrected copy would have gone stale the next time either side
  changed, which is exactly what had already happened to it.
- **The `409` is answered rather than reported.** Re-read the alert, say what it is now, offer the
  same move again at the version that came back — or say why it is no longer available. A stale
  version and an illegal transition reach a person as one sentence, because to a person they are
  one event.
- **Three controls were deleted, not migrated**, because Phase 6's gate is that there are none that
  do nothing: the queue's free-text search and risk-band filter (`GET /alerts` has neither), the
  feed's authorisation-status filter with the enum behind it (this system scores and never
  authorises), and the assignee picker.

**The assignee picker is the sharpest of the three, and it is worse than the audit thought.** The
audit recorded that an assignee renders as a UUID. The real position is that **this console cannot
assign an alert to anybody at all**: assignment takes an identifier, nothing resolves a name to one,
and the login response carries the operator's roles but not their own identifier — so not even
"assign to me" is buildable. Release, which sends `null`, is the only assignment it can honestly
make, and the screen says why.

**Nothing goes from a transaction to its alert either.** `GET /alerts` filters on status, priority
and assignee, and there is no lookup by transaction. The transaction page says so rather than
offering a link it would have to guess at.

### The console calls the API, and signs in to do it

The first of the audit's four pieces. `src/api/transport.ts` is `fetchBaseQuery` against
`API_BASE_URL` with the bearer token attached from the store, and a mapping that turns the API's
RFC 9457 problem documents into one `SentinelError` — keeping `detail`, `correlationId`, and the
extension properties (`legalTargets`, `currentVersion`) that make a `409` answerable rather than
merely reportable. `demoOperatorSlice` and the role selector are gone; `sessionSlice` holds a real
token, in memory and nowhere else.

**Endpoints are migrated one at a time, and say which they are.** `transport: "mock"` marks an
endpoint with no server counterpart. Only `POST /auth/login` is real today. A single global flag
would have to claim the console is entirely real or entirely fake, and it is neither for as long as
the migration takes.

**`expired` is a distinct session state from `anonymous`.** A token that ran out mid-review and a
browser that never had one need different sentences on the sign-in screen; collapsing them tells an
analyst who just lost a note that nothing happened. The `401` is handled once, in the base query,
so no screen can hold a credential it has been told is dead — and excluded for the login request
itself, where a `401` is a wrong password, and for anonymous requests, which must not turn "never
signed in" into "your session ended".

**A reload signs the operator out.** That is the honest consequence of ADR-0012 §3 and of the rule
against session state in browser storage, so the sign-in screen says so rather than letting it be a
surprise.

### Two things the transport work found that the audit had not

- **The API had no CORS configuration at all.** The console has never made a request, so nothing had
  ever had to answer how a browser is allowed to make one across the origin boundary ADR-0002
  created. [ADR-0013](docs/adr/0013-console-to-api-cross-origin-access.md) decides it: an explicit
  allow-list from `SENTINELFLOW_CORS_ALLOWED_ORIGINS`, registered for `/api/v1/**` only, no
  wildcard, no credentials. Proxying through the console's nginx was rejected — it needs no CORS at
  all and quietly removes the premise ADR-0012 §1 rests on when it rejects cookie sessions.
- **`TokenResponse` grew `roles`.** The audit said "roles read from the token"; the contract says a
  client that parsed the token would be reading a structure the service is free to change. Sending
  them beside it, from the same call that puts them in the claim, is the honest form. A test asserts
  the response and the claim are equal — if they could disagree, an operator would be offered a
  control under one capacity and audited under another.

### Three smaller things, recorded because each was a defect rather than a preference

- **The Vite dev server defaulted to port 8080**, which is the API's published port. Harmless while
  the console called nothing; now it is `make up && bun run dev` fighting over one port, with Vite
  silently taking another and changing the origin the API was told to expect. Pinned to 5174.
- **`API_BASE_URL` defaulted to the relative `/api/v1`**, which against the console's own nginx
  returns the SPA shell with a `200` the client would then parse as JSON — a worse failure than a 404. It is absolute now, and a build argument, because Vite inlines `VITE_*`.
- **The `/forbidden` screen told operators to "switch the simulated role in the header".** That
  control no longer exists. Dead copy, and Phase 6's gate is "no dead controls".

### Four e2e tests had been passing by accident

The suite now stubs the API at the network boundary, which is what lets the real transport, the real
bearer header and the real `401` be exercised in CI with no backend anywhere. Four keyboard and
timing tests had depended on a full page load — the tab order started wherever the last click left
it, and a reload drops the token by design. They say what they mean now.

### The migration is four pieces of work, not the one `AGENTS.md` describes

`AGENTS.md` says the mock-to-real migration is "limited to replacing `mockBaseQuery` with
`fetchBaseQuery`". [`docs/frontend/API_MIGRATION_AUDIT.md`](docs/frontend/API_MIGRATION_AUDIT.md)
checked that endpoint by endpoint against the contract and the handlers. It is not: of the console's
eleven endpoints, two reach a real endpoint at the same verb and path and still need every field
renamed, four have no server counterpart at all, and five server endpoints have no client.

The audit is the authoritative list. The four pieces it identifies:

| Piece                        | State                                                                                |
| ---------------------------- | ------------------------------------------------------------------------------------ |
| Transport and authentication | **done** — [#59](https://github.com/la3679/sentinelflow/pull/59)                     |
| Types and mapping            | **done** — [#63](https://github.com/la3679/sentinelflow/pull/63)                     |
| Two small API additions      | **one of two done** — legal targets merged; the assignee name is undecided           |
| The four invented endpoints  | **done** — ADR-0014, merged as [#65](https://github.com/la3679/sentinelflow/pull/65) |

**The audit is closed.** `src/mocks/` is deleted, and `USING_MOCK_DATA` with it.

### The console offers buttons the server refuses, which is a gate failure

`ALLOWED_TRANSITIONS` in `domain/types.ts` is a second copy of the alert state machine and it
disagrees with `AlertTransitions.java` **in both directions**: the console offers
`NEW → DISMISSED_FALSE_POSITIVE` and `CONFIRMED_SUSPICIOUS → CLOSED`, both of which answer `409`, and
hides four legal moves. Phase 6's gate is "no dead controls" and a button that always fails is one.

**Resolved on the server side, which is what lets the copy be deleted rather than corrected.**
`GET /alerts/{id}` and every queue row now carry `legalTargets` — the moves **this reader** may make,
so an analyst is not offered the administrative close and an auditor is offered nothing. Answering
"legal from this status" instead would have moved the rule into the client rather than removing it.

`AlertTransitions.namesOf` produces both that field and the `legalTargets` on the `409`, so a client
comparing what it was offered against what a refusal names cannot be shown two answers. A test
asserts they are equal.

**Deleting `ALLOWED_TRANSITIONS` was the console's half, and [#63](https://github.com/la3679/sentinelflow/pull/63) did it.**

### Three findings from the audit that are decisions rather than mapping

- **`assigneeId` is a UUID and nothing resolves it to a name.** An assignee column can currently
  render nothing a person recognises. Either the alert grows a display name or there is a small user
  lookup; undecided, and it is a real decision rather than a detail.
- **`GET /overview` has no counterpart and it is the landing page.** Every part exists — counts in
  the alert summary, lag and latency in Prometheus — and no endpoint composes them. An aggregate
  endpoint is a second place risk-band counting lives; a client-side composition is a screen that
  fires five requests and can be half-loaded. Undecided.
- **Two enums describe a product this is not.** `TransactionStatus: AUTHORIZED | DECLINED | …` says a
  payment switch decided something; this system scores and never decides. `AlertPriority: P1–P4`
  against the API's `LOW | MEDIUM | HIGH | URGENT`.

## Acceptance criteria status — Phase 7 gate

**Closed on 2026-08-31.** All four criteria are met with evidence from runs that happened. The four
are `docs/planning/IMPLEMENTATION_PLAN.md`'s. Each row says what the evidence covers **and what it
does not**, because a criterion whose limits are not written down is one somebody will over-read.

| Criterion                                        | Status  | Evidence, and what it does not cover                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| ------------------------------------------------ | ------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| One transaction trace can be followed end to end | **met** | Trace `c591f5172d73068c9f55902c1777d29d` on the running stack, 2026-08-30: seven spans in one trace, `http post /api/v1/transactions` at the root, `transaction.created.v1 process` beneath it — the consumer continuing across the outbox — and the scoring call beneath that. The scoring service's own log line for that transaction carries the same trace id. **Not covered:** the scoring service emits no spans of its own; its hop is the caller's.                            |
| Dashboards load with data                        | **met** | All 48 panel queries run against the live Prometheus: 0 empty, 0 malformed, against an API restarted less than a minute earlier. Grafana lists all five as provisioned. **Not covered:** the queries were run, not the rendering; a panel misconfigured in a way that does not change its query would still pass this.                                                                                                                                                                 |
| Redaction tests pass                             | **met** | `LogRedactionIT` (8) at `logging.level.root=DEBUG`, pinning nothing itself, over five paths — ingestion, the transaction read path, the alert workflow, reporting and signing in — with a planted amount, device handle, idempotency key, password and bearer token. `tests/test_log_redaction.py` (6) over all three scoring outcomes. **Not covered:** the drills' own contexts run at the shipped level, so a leak that occurs only while degraded would not be caught here.        |
| A documented failure drill succeeds              | **met** | Two, both tests rather than documents. `ScoringOutageDrillIT`: thirty transactions assessed while scoring refused, all degraded, none dead-lettered, and the outage cost five records' worth of HTTP attempts rather than thirty. `BrokerOutageDrillIT`: ingestion kept answering `202` with the broker frozen, the outbox retained, and the backlog drained with one ledger row per event. **Not covered:** the broker drill freezes rather than refuses — see the drill notes above. |

**All six deliverables from the plan are merged.** `infra/prometheus/prometheus.yml` no longer
carries `rule_files: []`; thirteen rules are loaded from `infra/prometheus/rules/sentinelflow.yml`,
each annotated with the runbook section that answers it.

**What Phase 7 deliberately did not deliver**, so a later session does not read the gate as covering
it: no Alertmanager, so no rule pages anybody; no threshold calibrated against a measured baseline,
because Phase 9 measures; and no spans from the scoring service, argued in
`apps/scoring/src/sentinelflow_scoring/trace.py`.

## Acceptance criteria status — Phase 6 gate

The four criteria are `docs/planning/IMPLEMENTATION_PLAN.md`'s. Each row names the evidence, because
a criterion asserted rather than evidenced is not met.

| Criterion                          | Status   | Evidence                                                                                                                                                                                                                                                                                                                                                                                        |
| ---------------------------------- | -------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| No dead controls                   | **pass** | `ALLOWED_TRANSITIONS` deleted and every action rendered from the alert's own `legalTargets` ([#63](https://github.com/la3679/sentinelflow/pull/63)); three panels of invented numbers deleted rather than sourced ([#65](https://github.com/la3679/sentinelflow/pull/65)); the queue's filters are exactly `GET /alerts`'s parameters and there is no search box, because the API has no search |
| Keyboard and accessibility checks  | **pass** | `console.spec.ts` — the skip link is the first tab stop and moves focus to the main landmark, navigation is operable by keyboard, focused controls have a visible indicator; axe (WCAG 2.1 A/AA) on all eight routes at desktop and tablet. 82 tests passed 2026-08-29                                                                                                                          |
| The core end-to-end journey passes | **pass** | Sign in, work the queue, open an alert, make a transition, and answer a `409` by re-reading and offering the move again — plus the transaction reads, the model and health screens, and the report with its CSV export. Same suite, same run                                                                                                                                                    |
| Lovable diffs reviewed and merged  | **n/a**  | There are none. Generation was retired in Phase 1 when the console moved to `apps/web/` (ADR-0002), and no `design/lovable-*` branch has existed since. Recorded as not-applicable rather than passed                                                                                                                                                                                           |

**Every deliverable in the plan's Phase 6 list is built:** the typed RTK Query layer with the mock
fixtures deleted, authentication against the real API, all eight screens, loading/empty/error and
permission-denied states on every data view, axe and Playwright, current screenshots, and
[ADR-0015](docs/adr/0015-live-updates-polling-and-server-sent-events.md).

### The phase closes with three deviations, stated rather than glossed

1. **No screen-reader pass happened.** Every accessibility check here is automated, and axe finds
   roughly a third of real issues. A pass with an actual screen reader needs a person using one; it
   has not happened and is not scheduled. The README now says so in those words rather than
   describing it as Phase 6 work.
2. **An alert can be released but not given to anybody.** How an assignee's identifier resolves to a
   person is still undecided, and the console says so on the screen instead of offering a control
   that cannot work. That is not a dead control, but it is a deliberate hole in the workflow.
3. **The authenticated console was not walked by hand in a browser.** What was done instead, against
   the live stack on 2026-08-29: every screen's endpoints were called directly with a real token,
   including the two that had never been exercised against the real services, and the console was
   loaded in a browser far enough to prove the cross-origin path end to end — the preflight answered
   `200` and a refused sign-in rendered as "The username and password were not accepted." rather
   than as a network failure, which is the distinction ADR-0013's configuration exists to make.
   Typing the demo operator's password into the form was not something this session would do; the
   remaining visual walk is a two-minute job for a person with the credential.

### What running the stack found, and the suites did not

`GET /models/active` had never been called against the real scoring service, which was the point of
the exercise. It answers, composed from the artifact the service loads from disk and the policy the
API owns. `GET /system/health` reports all three components operational. The full pipeline works:
six posted transactions were scored — not degraded — banded HIGH, and raised `ALT-0026` through
`ALT-0031` with `VELOCITY_5M_HIGH` as the leading factor.

**It also found a defect that no suite could have.** Every `transaction.created` event was being
dead-lettered after five attempts, because the `system` principal was missing from the local
database and `AlertRaiser` reads it to attribute an automated action. Ingestion still answered
`202`, health still reported everything operational, and the alert queue was still honestly empty —
so nothing anywhere pointed at it. The API now refuses to start against a database missing that row,
`MigrationIT` asserts the migrations put it there, and Runbook 1 records the generalisation.

**The instruction that caused it was in this file**, and it is corrected below: truncating `users`
to shrink the seed profile deletes reference data the seed does not write back.

## Acceptance criteria status — Phase 5 gate

The four criteria are `docs/planning/IMPLEMENTATION_PLAN.md`'s. Each row names the test that
demonstrates it, because a criterion asserted rather than evidenced is not met.

| Criterion                      | Status   | Evidence                                                                                                                                                            |
| ------------------------------ | -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Every state change audited     | **pass** | `AlertServiceIT` — "every move is written to the history with its actor and both ends"; 4 of the 5 writable action types covered, see below                         |
| Invalid changes handled        | **pass** | `AlertTransitionIT` — an illegal move is a 409 naming the legal targets; `AlertServiceIT` — a terminal alert refuses every move                                     |
| Concurrent changes handled     | **pass** | `AlertTransitionIT` — "a stale version is a conflict that names the version the alert is actually at"; a request with no version is refused before anything is read |
| Auditor mutation attempts fail | **pass** | Four refusals, one per mutating endpoint: transition, assignment, note, feedback                                                                                    |
| API documented                 | **pass** | Fourteen paths in `contracts/openapi/sentinelflow-api.yaml`, validated by `make contracts-check` in CI                                                              |

**CI green, verified.** All ten required checks passed on
[#49](https://github.com/la3679/sentinelflow/pull/49),
[#50](https://github.com/la3679/sentinelflow/pull/50),
[#51](https://github.com/la3679/sentinelflow/pull/51) and
[#52](https://github.com/la3679/sentinelflow/pull/52). The api job ran the Testcontainers suites on
the runner rather than merely compiling them — its log for #52 shows **189 unit and 250 integration
tests** and the coverage gate met at LINE 0.80 and BRANCH 0.70, against actual line 0.8972 and
branch 0.7945.

### Every state change audited — what "every" covers, exactly

`alert_actions` has five action types anything writes, and four are produced by Phase 5 code:

- **`CREATED`** — `AlertRaiser`, in the assessment's own transaction, attributed to the system
  principal. `RiskAssessmentWorkflowIT` covers the alert, its summary, its history row and its event.
- **`ASSIGNED` / `UNASSIGNED`** — `AlertService.assign`, which writes **nothing** when the assignee
  is unchanged. That is deliberate and tested ("assigning the same person twice writes nothing the
  second time"): an audit row saying an alert was given to whoever already held it is noise in the
  one place noise is most expensive.
- **`TRANSITIONED`** — `AlertService.transition`, with both ends of the move, enforced by the
  schema's own `alert_actions_transition_complete` CHECK rather than by the code alone.
- **`NOTE_ADDED`** — `AlertService.addNote`.

**`PRIORITY_CHANGED` is reserved by the schema and produced by nothing**, because no endpoint changes
an alert's priority — the priority comes from the band through `priorityByBand` and nothing overrides
it. Stated here rather than quietly left: V4 is merged and therefore immutable, so the CHECK
constraint keeps a value the application cannot currently write, and removing the enum constant would
put the enum and the constraint out of step for a reader to rediscover later. It is a gap in the
product, not in the audit trail.

**Analyst feedback writes no `alert_actions` row, and that is a decision rather than an omission.** A
verdict is not something done _to_ the alert — it does not move it, assign it, or change what a queue
shows. It is a training label about the _assessment_, in `analyst_feedback`, with its own actor and
timestamp. Whether the alert was dispositioned is already recorded, by the transition that
dispositioned it. `AlertQueueIT` covers the label, its revision, and its refusal on a closed alert.

### Concurrency is checked twice, and neither check is redundant

`expectedVersion` is compared on read and the write then relies on the JPA `@Version` column, so two
callers who both read version 3 cannot both write version 4. The first check gives the second caller
a 409 that names the version the alert is actually at, which is actionable; the second is what makes
the guarantee true under a race the first cannot see. `AlertServiceIT` covers the stale read and
`AlertTransitionIT` covers what a caller receives.

**A request with no `expectedVersion` is refused before anything is read.** Optional optimistic
concurrency is not optimistic concurrency: a client that omits the field would silently get
last-write-wins, which is the behaviour the field exists to prevent.

### The auditor refusals, one per mutating endpoint

`ADR-0012 §4` makes `AUDITOR` read-only, and read-only has to mean every mutation rather than the
memorable ones:

| Endpoint                       | Test                                                            |
| ------------------------------ | --------------------------------------------------------------- |
| `POST /alerts/{id}/transition` | "an auditor is refused by the server, not by a disabled button" |
| `PUT /alerts/{id}/assignment`  | "an auditor cannot assign an alert either"                      |
| `POST /alerts/{id}/notes`      | "an auditor cannot annotate an alert"                           |
| `PUT /alerts/{id}/feedback`    | "an auditor cannot record a verdict"                            |

**The note refusal was added to close this gate.** The `@PreAuthorize` was there from the start and
nothing exercised it, which is the same as not having it: an annotation nobody tests is a claim, and
this criterion asks for evidence. A note is the quietest thing an auditor could add and the easiest
guard to drop by accident.

**An auditor can read everything**, including the audit trail and both reports. Read-only describes
what somebody may do, not what they may see, and an auditor who could not read the history would be a
contradiction.

### Two deviations from the phase's deliverable list, stated rather than glossed

**"Paginated reporting endpoints" — neither report is paged, deliberately.** The summary is a fixed
size: six statuses, four priorities and four bands, however many alerts the window holds, so a page
parameter would be one nobody could use. The export is _capped_ at 10,000 rows and refuses a wider
window with `413`, because a report somebody opens in a spreadsheet is a file rather than a cursor
and stitching four pages together in Excel is how a report becomes wrong. Both satisfy the rule the
pagination requirement exists to enforce — `.claude/rules/java.md`'s "an endpoint whose result grows
with the dataset is a denial-of-service primitive" — without pretending a cursor is the right shape
for a download.

**`POST /api/v1/transactions` is still unauthenticated** (ADR-0012 §5). It is a machine-to-machine
surface whose caller is a payment switch rather than a person, and giving it a credential is Phase 8's
work. Recorded here because a phase whose gate includes role authorization should say plainly which
endpoint has none.

### What this gate does not cover

The same limitation the Phase 4 gate names, and for the same reason: `make smoke` has not been run
since the actuator's closed endpoints began answering 401 rather than 404, and the Testcontainers
suites and the compose stack are not the same system. Three Phase 4 defects were invisible to a green
build, and this phase added a fourth kind — a reference collision invisible to a green _local_ build.
A green gate is evidence about the code under one interleaving, not about the deployment the demo
runs on. Phase 9's clean-clone check is where that distinction gets its own gate.

## Acceptance criteria status — Phase 4 gate

| Criterion                                       | Status   | Evidence                                                                                 |
| ----------------------------------------------- | -------- | ---------------------------------------------------------------------------------------- |
| Training reproducible from a documented command | **pass** | `make train`, from a fingerprinted export at a recorded seed; ADR-0010 §6                |
| Evaluation report generated                     | **pass** | `docs/ml/EVALUATION.md` and `docs/ml/MODEL_CARD.md`, both generated, never hand-written  |
| Model checksum and version stored               | **pass** | `manifest.json` carries the artifact SHA-256; loading verifies it before serving         |
| Service contracts tested                        | **pass** | `ScoringPayloadContractIT`, `RiskAssessedContractIT`, and `make contracts-check`         |
| Failure behaviour tested                        | **pass** | `RiskAssessmentWorkflowIT` — scored, degraded and rejected, against PostgreSQL and Kafka |

**The fourth criterion was the one that needed the workflow.** Until Phase 4's last piece,
`ScoringClientTests` covered the client's own three outcomes and nothing covered what the pipeline
did with them. `RiskAssessmentWorkflowIT` closes that: a 503 degrades and still marks the transaction
`ASSESSED`, a 422 writes no assessment and dead-letters the record after one attempt, and a
redelivered event produces exactly one assessment and one outbox row.

**CI green, verified.** All ten required checks passed on #47, and the api job ran the Testcontainers
suites on the runner rather than merely compiling them — 147 unit and 172 integration tests, coverage
gate met at LINE 0.80 and BRANCH 0.70.

**One thing the gate does not cover, stated rather than left implicit.** Three defects in this phase
were invisible to every suite and were found by running the compose stack. A green gate is evidence
about the code, not about the deployment the demo runs on, and Phase 9's clean-clone check is where
that distinction gets its own gate.

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

### 2026-08-31 — Phase 7 closed: two drills, nine runbooks, thirteen rules, four log leaks

| Check                                                       | Result                                                                   |
| ----------------------------------------------------------- | ------------------------------------------------------------------------ |
| `./mvnw -B verify` (apps/api, JDK 25.0.4.1)                 | **PASS** — 233 unit and 309 integration tests, 0 failures, 0 errors      |
| JaCoCo gate                                                 | **met** — "All coverage checks have been met"                            |
| `uv run pytest` (apps/scoring)                              | **PASS** — 187 passed in 139.59s                                         |
| `uv run ruff check .` · `ruff format --check` · `mypy`      | **PASS** — no issues over 46 source files                                |
| `promtool check rules infra/prometheus/rules/*.yml`         | **PASS** — 13 rules found                                                |
| `promtool check config infra/prometheus/prometheus.yml`     | **PASS** — valid syntax, 1 rule file, 13 rules                           |
| All 13 rule expressions against the live Prometheus         | **13/13 parsed and evaluated**, and every metric each one reads exists   |
| Prometheus reloaded with the rules mounted                  | 13 rules loaded, **13 inactive, 0 evaluation errors**                    |
| Runbook anchors referenced by the rules                     | **13/13 resolve** to a heading in `docs/operations/RUNBOOKS.md`          |
| `bun scripts/dev/check-docs.mjs`                            | **PASS** — 215 links across 47 files, 0 broken, 0 placeholders           |
| CI on [#82](https://github.com/la3679/sentinelflow/pull/82) | **PASS** — all ten required checks; both drills ran on the Ubuntu runner |

**The drills, from the runs that closed the gate:**

| Drill                  | What it observed                                                                                                                                                       |
| ---------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ScoringOutageDrillIT` | 30 transactions assessed while scoring refused; 30/30 degraded with no model score; 0 dead-lettered; **5 records' worth of HTTP attempts, not 30**; recovery automatic |
| `BrokerOutageDrillIT`  | Every ingest still `202` with the broker frozen; 6 events held `PENDING` with `last_error` recorded; 0 `FAILED`; backlog drained to one ledger row per event           |
| Isolation of the pause | `RiskAssessmentWorkflowIT` green in the same run, immediately after the broker drill                                                                                   |

**The redaction widening, and what it found.** Raising `LogRedactionIT` to
`logging.level.root=DEBUG` over five paths turned 4 assertions into 8 and found **four leaks that no
narrower configuration could have shown**: Hibernate's entity dumps carrying an amount, a device
handle, an outbox payload and an analyst's note; `AlertNoteRequest` printing the note through
Spring's own `Read […]` line; and `TransactionResponse` being safe only because a framework log line
truncates at 100 characters. The bearer token and the password never appeared, which is the other
half of the result. Fixes: three pinned Hibernate loggers in `application.yaml`, and a redacting
`toString` on the four alert request records and on `TransactionResponse`.

### 2026-08-29 — Phase 6 closed: ADR-0015, the live stack, and the row that broke every alert

| Check                                                       | Result                                                              |
| ----------------------------------------------------------- | ------------------------------------------------------------------- |
| `./mvnw verify` (apps/api, JDK 25)                          | **PASS** — 226 unit and 290 integration tests, 0 failures           |
| JaCoCo gate                                                 | **met** — line 0.8955, branch 0.7931                                |
| `bun run lint` (apps/web)                                   | **PASS** — 0 errors, 26 pre-existing `react-refresh` warnings       |
| `bun run typecheck` (apps/web)                              | **PASS**                                                            |
| `bun run test` (apps/web)                                   | **PASS** — 41 tests in 5 files, up from 37                          |
| `bun run test:coverage` (apps/web)                          | 26.79% statements, 18.61% branches, 19.81% functions, 26.93% lines  |
| `bun run test:e2e`                                          | **PASS** — 82 tests, axe clean on all eight routes at two viewports |
| `bun run build` (apps/web)                                  | **PASS**                                                            |
| `format:check` · `check-docs.mjs`                           | **PASS** — 204 links across 46 files, 0 broken                      |
| CI on [#67](https://github.com/la3679/sentinelflow/pull/67) | **PASS** — all ten required checks, on head `e058f84`               |

**Against the running stack**, after `docker compose up -d --build` rebuilt every image:

| Call                                  | Result                                                                                          |
| ------------------------------------- | ----------------------------------------------------------------------------------------------- |
| `POST /auth/login`                    | `200`, token issued for `analyst.one`                                                           |
| `GET /models/active`                  | `200`, `modelAvailable: true` — the first time this reached the real scoring service            |
| `GET /system/health`                  | `200`, all three components `OPERATIONAL`                                                       |
| `POST /transactions` ×6               | `202` each; scored rather than degraded, banded HIGH, raising `ALT-0026`–`ALT-0031`             |
| Console sign-in from `localhost:5173` | CORS preflight `200`, refused sign-in rendered as a `401` message rather than a network failure |

**The first attempt at those six transactions failed**, and that is the finding: every event was
retried five times and dead-lettered on an `IllegalStateException` while ingestion answered `202`
and health reported everything operational. The `system` principal was missing from the local
database. Fixed at the source with a startup refusal — see "What running the stack found" above.

### 2026-08-28 — Phase 6, the last four screens

| Check                                 | Result                                                                        |
| ------------------------------------- | ----------------------------------------------------------------------------- |
| `./mvnw verify` (apps/api)            | **PASS** — 209 unit and 289 integration tests, 0 failures, JDK 25.0.4.1+1     |
| JaCoCo gate (LINE 0.80, BRANCH 0.70)  | **met** — line 0.8953, branch 0.7931, instruction 0.9040                      |
| `PlatformReadIT`                      | **10 passed** — the two new endpoints, against real PostgreSQL                |
| `ScoringClientTests`                  | **17 passed** — 5 of them the model-metadata read                             |
| `bun run verify` (apps/web)           | **PASS** — lint clean, typecheck clean, 37 unit tests in 5 files, build built |
| `bun run test:e2e` (apps/web)         | **PASS** — 82 tests, desktop and tablet, axe clean on all eight routes        |
| `bun scripts/dev/check-contracts.mjs` | **PASS**                                                                      |
| `bun scripts/dev/check-docs.mjs`      | **PASS** — 184 links across 44 files, 0 broken                                |
| `bun run format:check`                | **PASS**                                                                      |

**The unit-test count fell from 41 to 37 and that is the deletion, not a regression.** The suite
that asserted the fixture layer's determinism went with the fixture layer; four of its assertions
had nothing left to describe. The transport test that asserted the overview made _no_ network
request was inverted rather than deleted, so the change is visible to a reader rather than silent.

**Not run this session:** `make smoke`, the compose stack, and `apps/scoring`'s suite. The scoring
service was not touched, though the API now reads its `/v1/model` — which is worth exercising
against the live stack before Phase 6 closes.

### 2026-08-28 — Phase 6, the transaction endpoints and the typed console

| Check                                 | Result                                                                        |
| ------------------------------------- | ----------------------------------------------------------------------------- |
| `./mvnw verify` (apps/api)            | **PASS** — 204 unit and 279 integration tests, 0 failures, JDK 25.0.4.1+1     |
| JaCoCo gate (LINE 0.80, BRANCH 0.70)  | **met** — line 0.8991, branch 0.8030, instruction 0.9088                      |
| `TransactionReadIT`                   | **16 passed**, against real PostgreSQL — the new suite                        |
| `bun run verify` (apps/web)           | **PASS** — lint clean, typecheck clean, 41 unit tests in 6 files, build built |
| `bun run test:e2e` (apps/web)         | **PASS** — 72 tests, desktop and tablet, axe clean on all eight routes        |
| `bun scripts/dev/check-contracts.mjs` | **PASS** — every schema, example and API document                             |
| `bun scripts/dev/check-docs.mjs`      | **PASS** — 182 links across 44 files, 0 broken, no placeholders               |
| `bun run format:check`                | **PASS**                                                                      |

Merged as [#62](https://github.com/la3679/sentinelflow/pull/62) and
[#63](https://github.com/la3679/sentinelflow/pull/63); all ten required checks passed on each.

**The e2e stub answers in the contract's shapes, field for field.** That is what makes 72 passing
tests against no backend meaningful: a screen that passes there cannot break against the real API
for a reason the suite could have caught. Two of the new tests are the ones that would have caught
this session's own bugs — that only `legalTargets` moves are offered, and that a `409` re-reads the
alert and offers the move again.

**Not run this session:** `make smoke`, the compose stack, and `apps/scoring`'s suite. The scoring
service was not touched.

**The README screenshots were regenerated** from the same production bundle, so the alert queue
image shows the columns the API actually serves rather than the fixture's.

### 2026-08-28 — Phase 6, the transport and the real sign-in

| Check                                              | Result                                                                        |
| -------------------------------------------------- | ----------------------------------------------------------------------------- |
| `bun run verify` (apps/web)                        | **PASS** — lint clean, typecheck clean, 38 unit tests in 6 files, build built |
| `bun run test:e2e` (apps/web)                      | **PASS** — 68 tests, desktop and tablet, axe clean on all eight routes        |
| `./mvnw verify -Dit.test=OperatorAuthenticationIT` | **PASS** — 12 integration tests, JDK 25.0.4.1+1                               |
| `./mvnw test -Dtest=CorsPropertiesTests`           | **PASS** — 9 unit tests                                                       |

Four commits, merged as [#59](https://github.com/la3679/sentinelflow/pull/59): the roles on the
token response, the CORS allow-list and ADR-0013, the console's transport with its session, and the
documentation. **All ten required checks passed on the pull request**, and all six workflows passed
on the merge commit `90497a6` on `main`.

**Unit and integration test counts for `apps/api` as a whole were not re-run in this session.** Only
the two suites this change touches were. The last full number is the one recorded against `9580a96`
below.

Every figure below came from a run on the date its section names. Nothing here is estimated.

### 2026-08-28 — `make smoke` run at last, and the defect it found

**Both smoke paths, 23 passed and 0 failed.** `scripts/smoke/smoke.sh` and its PowerShell equivalent
`scripts/dev/sf.ps1 smoke`, neither of which had ever been executed since they were updated to expect
401 from the actuator's closed endpoints.

**The first run failed two checks, and the script was right both times.** `/actuator/env` and
`/actuator/beans` answered 404 rather than 401 because the running api image was **19 hours old** and
predated ADR-0012's authentication. `docker compose up -d --build` and both went green. Worth keeping
because the failure looked exactly like a wrong expectation and was a stale artefact — the smoke test
asks the _running_ stack, and the running stack is only as current as the last build.

**Then no operator could log in.** See the section below; it is the most consequential thing this
session found and no suite could have found it.

**The two reporting endpoints were exercised over HTTP against the live stack**, which is the check
the Testcontainers suites cannot make:

| Call                                       | Result                                                                                    |
| ------------------------------------------ | ----------------------------------------------------------------------------------------- |
| `GET /reports/alert-summary` as an analyst | 200, every enum key present including the zeroes, exactly as the contract says            |
| `GET /reports/alerts.csv` as an analyst    | 200, `text/csv;charset=UTF-8`, `attachment; filename="sentinelflow-alerts-…"`, header row |
| the same summary with `from` after `to`    | 422                                                                                       |
| the same summary with no token             | 401                                                                                       |

The window held no alerts, and that is the database rather than the endpoint: this demo database was
populated before alert creation existed and every assessment in it is degraded, so there is nothing
for an alert to have been raised from. `make reset-demo` then `make seed` gives one with alerts.

### The smoke test found a defect that made the whole console unusable

**Every one of the four demo operators answered 401, on a stack reporting 23 of 23 smoke checks
green.** `user_credentials` was empty. The four users were seeded on 2026-08-27 and V10, which
created that table, arrived on 2026-08-28.

**`alreadySeeded()` asks whether any customer exists** and takes that as meaning everything the seed
loader writes exists. That proxy held until the seed learned to write a new kind of row, which it
just had. Any database seeded before V10 therefore has four operators, no passwords, and a seed that
will never repair them because it can see customers.

**The symptom is silent by design, which is what makes it bad.** ADR-0012 §3 requires that a refusal
never say why — distinguishing an unknown username from a wrong password would turn an endpoint that
must be open into an oracle for which usernames exist. So the console simply stops working, nothing
logs anything, and the only documented route out is `make reset-demo`, which destroys the data.

**Fixed by repairing rather than by resetting.** `DeterministicSeedLoader` now creates a missing
operator, their role and their credential even on the skipped path, and logs at WARN how many needed
it. It **never rotates an existing credential** — changing `SENTINELFLOW_DEMO_OPERATOR_PASSWORD` and
restarting must not silently reset four passwords, and a repair that overwrote what it found could not
be run safely. It works from the four fixed usernames, so the `system` principal is not something it
can reach rather than something it declines to touch.

**Verified on the live stack**: the WARN fired naming four operators, and `analyst.one` then logged
in against the running API. Three tests pin it, including the one that keeps it a repair rather than
a reset.

**The generalisable part:** an idempotency guard that tests a proxy for its work rather than the work
itself is correct only until the work changes. This is the same shape as the reference collision
earlier in the session — a check that was true when written and silently stopped being true.

### 2026-08-28 — Phase 5, reporting made green and put under contract

Every suite, on `feat/alert-reporting` at `e7cc4ba`, under `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1`.

| Command                                         | Result                                                   |
| ----------------------------------------------- | -------------------------------------------------------- |
| `mvnw -B verify` (api, unit)                    | **189 passed**, 0 failures, 0 errors, 0 skipped          |
| `mvnw -B verify` (api, Testcontainers)          | **250 passed**, 0 failures, 0 errors, 0 skipped          |
| JaCoCo gate (LINE 0.80, BRANCH 0.70)            | **met** — line 0.8972, branch 0.7945, instruction 0.9080 |
| `bun run test` (web)                            | **24 passed**, 5 files                                   |
| `uv run pytest` (scoring)                       | **169 passed** in 164s                                   |
| `bun run lint` (web)                            | 0 errors, 23 pre-existing `react-refresh` warnings       |
| `uv run ruff check .` · `uv run mypy` (scoring) | clean · no issues in 42 source files                     |
| `mvnw spotless:check` (api)                     | 221 files clean                                          |
| `bun scripts/dev/check-contracts.mjs`           | all contract checks passed                               |
| `bun run format:check` · `check-docs.mjs`       | clean · 160 links, no placeholders                       |

**`AlertReportIT` is 10 of the 250 and all 10 pass.** The four that were red were red because of the
fixture, not the endpoints; the diff that fixed them touches only the test class.

**Coverage on the reporting code specifically:** `ReportController` and `CsvWriter` at 1.0000
instruction coverage, `AlertReportService` at 0.9589. The uncovered part of the service is the
export cap's refusal branch, which needs 10,001 alerts in one window to reach — noted rather than
faked with a lowered constant, since a cap the test moves is not the cap that ships.

**The 250 was 248 before CI found a defect the local run could not.** See below; the two extra
tests are `ReferenceAllocationIT`.

**Both figures are the build's own `Results:` line**, and the runner printed the same two — 189 and 250. An earlier draft of this section said 203 unit tests, summed from the JUnit XML files, which
counts a `@Nested` class's cases in both the container's file and its own. The console figure is the
one to quote, because it is the one CI reports and the one a reader reproduces by running the
command.

**Not run this session:** `make smoke`, `make test-e2e`, and the compose stack. The standing note
below about the actuator's 401 still applies.

### CI found a reference collision that a green local run had hidden

**#52's first run failed one test**, and it was not a reporting test:
`TransactionIngestionIT.retryReturnsTheOriginalResult` answered 500 rather than 202, on a duplicate
key for `TXN-000005`. The same commit was green locally, twice.

**Two allocators owned one namespace.** `SchemaFixtures` built `TXN-` and `ALT-` from an in-JVM
`AtomicInteger` starting at 1, while the application read `transaction_reference_seq` and
`alert_reference_seq`, which also start at 1. One container serves the whole fork, so the two met as
soon as the application had ingested as many transactions as the fixtures had written — a point that
depends on the order the suites happen to run in, and so on the machine. The class comment asserted
the counter "starts high enough that a value never collides"; it started at 1.

**Fixed by deleting the second allocator rather than by moving it out of the way.** The fixtures draw
both references from the same sequences the application does, which makes a collision impossible
instead of unlikely — and unlikely is exactly what the old counter already was.

**`ReferenceAllocationIT` asserts the strong property.** Drawn alternately from the fixture and from
the application, every reference is exactly one more than the one before it, which only a single
shared sequence can produce. Asserting mere distinctness would pass with two counters standing far
apart, which is the state the old code was usually in. Verified to fail by reinstating the defect.

**The generalisable part:** a green local suite is evidence about one interleaving of the tests. This
is the second time in this project that a defect was invisible until it ran somewhere else — the
first was the three the compose stack found, recorded below.

### 2026-08-28 — Phase 5, reporting (an emergency checkpoint, not a finish)

| Command                                         | Result                                        |
| ----------------------------------------------- | --------------------------------------------- |
| `./mvnw verify -DskipITs -Dtest=CsvWriterTests` | **PASS** — 17 of 17                           |
| `./mvnw verify -Dit.test=AlertReportIT`         | **FAIL** — 6 of 10; the four count assertions |
| `./mvnw -DskipTests test-compile`               | **PASS**                                      |

**Stopped at 90% of the five-hour usage window, on the user's instruction.** The checkpoint policy in
`CLAUDE.md` applies: no further implementation, commit what is safe, push, record state. The failing
suite is committed with its diagnosis rather than deleted or disabled, because a test that was
removed to go green is worse than one that is red for a reason somebody wrote down.

Nothing else was re-run at this checkpoint, so the last full-suite figures are the entry below.

### 2026-08-28 — Phase 5, assignment, notes, feedback and the queue reads

| Command                                    | Result                                            |
| ------------------------------------------ | ------------------------------------------------- |
| `./mvnw verify -DskipITs` (JDK 25.0.4.1+1) | **PASS** — 172 unit tests                         |
| `./mvnw verify` (both suites)              | **PASS** — 238 integration tests, coverage met    |
| JaCoCo, both suites                        | 89.8% lines (2365/2634), 78.6% branches (460/585) |
| `bun run format:check`                     | **PASS** — repository-wide                        |
| `bun scripts/dev/check-docs.mjs`           | **PASS** — 160 links across 42 files              |
| `bun scripts/dev/check-contracts.mjs`      | **PASS** — every schema, example and API document |

**One defect found by running it.** An oversize page size answered 500. `@Validated` puts a
controller behind a proxy and routes parameter constraints through Hibernate Validator's
`ConstraintViolationException`, while Spring MVC's built-in method validation — which needs no
annotation when a parameter carries a constraint — raises `HandlerMethodValidationException`. The
annotation was removed so there is one mechanism, and that exception now maps to the same 422 a body
validation failure produces.

Still nothing run against the compose stack.

### 2026-08-28 — Phase 5, the investigation state machine and ADR-0012

| Command                                    | Result                                            |
| ------------------------------------------ | ------------------------------------------------- |
| `./mvnw verify -DskipITs` (JDK 25.0.4.1+1) | **PASS** — 172 unit tests                         |
| `./mvnw verify -DskipUnitTests=true`       | **PASS** — 211 integration tests                  |
| `./mvnw verify` (both suites)              | **PASS** — every coverage check met               |
| JaCoCo, both suites                        | 88.3% lines (2212/2506), 78.1% branches (438/561) |
| `bun run format:check`                     | **PASS** — repository-wide                        |
| `bun scripts/dev/check-docs.mjs`           | **PASS** — 160 links across 42 files              |
| `bun scripts/dev/check-contracts.mjs`      | **PASS** — every schema, example and API document |
| `docker compose config`                    | **PASS** — after adding the two new secrets       |

Line coverage rose from 87.0% to 88.3% and branch coverage from 77.8% to 78.1%.

**Not demonstrated on the compose stack.** No transition has been made against a running system, and
`make smoke` has not been re-run since the actuator's closed endpoints started answering 401 rather
than 404 — the script and its PowerShell equivalent were updated to expect it, and neither has been
executed. That is the first thing to do on a machine with the stack up.

### 2026-08-28 — Phase 5, the alert path covered end to end, and ADR-0011 §4

| Command                                    | Result                                            |
| ------------------------------------------ | ------------------------------------------------- |
| `./mvnw verify -DskipITs` (JDK 25.0.4.1+1) | **PASS** — 156 unit tests                         |
| `./mvnw verify -DskipUnitTests=true`       | **PASS** — 181 integration tests                  |
| `./mvnw verify` (both suites)              | **PASS** — every coverage check met               |
| JaCoCo, both suites                        | 87.0% lines (1907/2193), 77.8% branches (388/499) |
| `bun run format:check`                     | **PASS** — repository-wide                        |
| `bun scripts/dev/check-docs.mjs`           | **PASS** — 152 links across 41 files              |
| `bun scripts/dev/check-contracts.mjs`      | **PASS** — every schema, example and API document |

**Line coverage recovered from 83.4% to 87.0% and branch coverage from 77.0% to 77.8%**, which is
what the previous entry said the missing integration test was worth. Nothing was written to move a
coverage number; the figures moved because `AlertRaiser` is now executed by tests that assert what it
wrote.

`apps/web` and `apps/scoring` were not re-run; nothing in either changed.

Still not demonstrated on the compose stack. The alert path has been exercised against real
PostgreSQL and real Kafka in Testcontainers, and the previous session's three defects are why that is
recorded as a difference rather than a formality — a demo runs on the compose stack, and nothing has
raised an alert there yet.

### 2026-08-27 — Phase 5, the alerting policy and alert creation (checkpoint, not a finish)

| Command                          | Result                                            |
| -------------------------------- | ------------------------------------------------- |
| `./mvnw verify` (JDK 25.0.4.1+1) | **PASS** — 154 unit tests, 176 integration tests  |
| JaCoCo, both suites              | 83.4% lines (1830/2193), 77.0% branches (384/499) |
| `bun run format:check`           | **PASS** — repository-wide                        |
| `bun scripts/dev/check-docs.mjs` | **PASS** — 152 links across 41 files              |
| `make contracts-check`           | **PASS** — every schema, example and API document |

**Line coverage fell from 85.7% to 83.4%, and that is the honest number rather than a regression to
fix by writing a test that does not assert anything.** `AlertRaiser` and three new repositories have
unit and contract coverage and no integration coverage, because the integration test is the next
action. Both floors are still met — LINE 0.80, BRANCH 0.70 — and the ratchet is deliberately not
lowered. Branch coverage rose, because the policy's two new validators are fully exercised.

Nothing was run against the compose stack this session; the alert path has not been demonstrated on
a running system.

### 2026-08-27 — Phase 4, the assessment workflow and `make replay`

| Command                                    | Result                                                        |
| ------------------------------------------ | ------------------------------------------------------------- |
| `./mvnw verify` (JDK 25.0.4.1+1)           | **PASS** — 147 unit tests, 172 integration tests              |
| JaCoCo, both suites                        | 85.7% lines (1794/2093), 76.9% branches (362/471)             |
| `bun scripts/dev/check-contracts.mjs`      | **PASS** — every schema, example and API document             |
| `bun scripts/dev/check-docs.mjs`           | **PASS** — 154 links across 41 files                          |
| `bunx prettier --check`                    | **PASS** — every file touched                                 |
| `./scripts/dev/replay.sh` (both scenarios) | **PASS** — 4 degraded then 4 scored; DLQ +1; undeliverable +1 |
| `.\scripts\dev\sf.ps1 replay`              | **PASS** — same outcomes on the reference Windows path        |

Coverage ratcheted to LINE 0.80 and BRANCH 0.70, from 0.70 and 0.60.

**The h2c defect was found by running, not by testing.** `ScoringClientTests` and
`RiskAssessmentWorkflowIT` were both green while every request to the real scoring service was being
refused, because `com.sun.net.httpserver` ignores the upgrade attempt uvicorn refuses. The new
assertion is on the wire — no `Upgrade` header on any request — and was verified by removing the fix
and watching it fail.

`apps/scoring` was not re-run; nothing in it changed.

### 2026-08-27 — Phase 4, the scoring client and ADR-0011

| Command                                    | Result                                            |
| ------------------------------------------ | ------------------------------------------------- |
| `./mvnw verify -DskipITs` (JDK 25.0.4.1+1) | **PASS** — 132 unit tests                         |
| `./mvnw verify -DskipUnitTests=true`       | **PASS** — 158 integration tests, JaCoCo gate met |
| `bun run format:check` (repository-wide)   | **PASS**                                          |
| `bun scripts/dev/check-docs.mjs`           | **PASS** — 141 links across 40 files              |

The client is tested against a real socket rather than a mocked `RestClient`: a read timeout, a
refused connection and a 2xx with an empty body only exist at the transport, and a mock would pass
while the shipped timeouts were attached to nothing. `apps/scoring` was not re-run — nothing in it
changed.

### 2026-08-27 — Phase 4, the rules baseline

| Command                                          | Result                                                                                           |
| ------------------------------------------------ | ------------------------------------------------------------------------------------------------ |
| `./mvnw verify -DskipITs` (JDK 25.0.4.1+1)       | **PASS** — 91 unit tests                                                                         |
| `./mvnw verify -DskipUnitTests=true`             | **PASS** — 154 integration tests, JaCoCo gate met                                                |
| `uv run pytest --cov` (apps/scoring)             | **PASS** — 171 tests, 97.36% coverage, floor 90                                                  |
| `uv run mypy` (strict)                           | **PASS** — 0 issues, 42 source files                                                             |
| `uv run ruff check` / `ruff format --check`      | **PASS**                                                                                         |
| `make export-dataset` (LOCAL)                    | 20,707 examples, 707 planted, sha256 `8eb1bac8…`                                                 |
| `uv run python -m sentinelflow_scoring.training` | rules 0.2611 · logistic 0.8327 · boosting 0.8081 · iforest 0.7154                                |
| Rule-score distribution over the export          | fires on 23% of NORMAL, 100% of OFF_HOURS_NEW_DEVICE, 53% of VELOCITY_BURST, 52% of CARD_TESTING |
| `bun run format:check` (repository-wide)         | **PASS**                                                                                         |
| `bun scripts/dev/check-docs.mjs`                 | **PASS** — 141 links across 40 files                                                             |

The ruleset is a real floor rather than a formality: it separates planted shapes from background
traffic without being anywhere near good enough to make a model pointless. The model's margin over it
is 0.57 PR-AUC.

### 2026-08-27 — Phase 4, the inference API

| Command                                     | Result                                                          |
| ------------------------------------------- | --------------------------------------------------------------- |
| `uv run pytest --cov` (apps/scoring)        | **PASS** — 171 tests, 97.36% coverage, floor 90                 |
| The same, before this work                  | 94 tests, 96.75%                                                |
| `uv run mypy` (scoring, strict)             | **PASS** — 0 issues, 42 source files                            |
| `uv run ruff check` / `ruff format --check` | **PASS**                                                        |
| `docker build apps/scoring`                 | **PASS** — 610 MB                                               |
| Container `GET /health/ready`               | `{"status":"UP","modelLoaded":true}`                            |
| Container `GET /v1/model`                   | serves the committed manifest and its holdout figures           |
| Container `POST /v1/score`                  | 200 with ten reasons; correlation id echoed; bad body gives 422 |
| `/app/models` inside the image              | manifest, metrics, artifact — plots and card excluded           |
| `bun run format:check` (repository-wide)    | **PASS**                                                        |
| `bun scripts/dev/check-docs.mjs`            | **PASS** — 141 links across 40 files, 0 broken, 0 placeholders  |
| `bun scripts/dev/check-contracts.mjs`       | **PASS** — all three API documents                              |

Every module under `serving/` measures 100% statement and branch coverage, and
`training/registry.py` reached 100% with the discovery and metrics-reading tests. The scoring floor
stays at 90: it has now measured above 95 three times, and ratcheting it mid-phase is churn — the
turn comes when Phase 4 closes.

The scoring service was **not** run under `make up` for this work. Everything above is the image
built from this tree, run alone with its published port; nothing here is a claim about the stack.

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
| 0010 | Model selection and evaluation: the comparison, the metrics, and training as a command              |
| 0011 | The final score is `max(rule, weighted mean)`, banded from configured bounds; §4 gates alerting     |
| 0012 | Operator authentication: a password for a short-lived JWT, credentials in their own table           |

**This table was three ADRs out of date**, and its "still needing" line was wrong about two of the
numbers as well. 0010 and 0011 were accepted in Phase 4 and 0012 in Phase 5; corrected against
`docs/adr/`, which is the authority.

**Still needing an ADR:** 0013 observability · 0014 deployment strategy · 0015 SSE versus WebSockets.
The numbers are the implementation plan's, not this file's.

**Contracts:** `contracts/` is validated in CI — OpenAPI 3.1 for the public `/api/v1`, OpenAPI 3.1
for the internal API-to-scoring boundary, AsyncAPI 3.0 for the five topics, and seven JSON Schemas.
`make contracts-check` compiles every schema, validates every example, asserts the
deliberately-invalid ones are rejected, and parses **every** document in `contracts/openapi/` rather
than one named file — which is what stops a second authoritative document from being one nothing
checks.

## Known issues and technical debt

- **`POST /api/v1/transactions` is unauthenticated**, deliberately and temporarily (ADR-0012 §5).
  Operator endpoints require a bearer token; ingestion does not, because it is a machine-to-machine
  surface that needs its own credential rather than an operator's password. Until Phase 8 gives it
  one, anything that can reach the API can submit a synthetic transaction. The demo stack binds to
  localhost.
- **A token cannot be revoked before it expires.** Thirty minutes is the whole of how long a
  withdrawn role keeps working. That is the cost of statelessness and it is accepted rather than
  overlooked; a revocation list is Phase 8's if the demo ever needs one.
- **`/actuator/prometheus` is open**, because a scrape cannot hold a token that expires every thirty
  minutes. The series are aggregate counters and timers with bounded labels — no identifier, no
  amount, no payload — so what it discloses is the shape of the traffic. The real answer is a
  management port that is not published to the host, which is Phase 8's hardening work.
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
- ~~**`compose.yaml` makes the API wait for scoring to be _healthy_.**~~ **Resolved 2026-08-27.**
  It is `service_started` now, in the branch that made degradation real. Ordering is still declared,
  so the ordinary case is a warm dependency rather than a first transaction scored against a
  container still importing scikit-learn.
- **Coverage thresholds are enforced in `apps/api` only** — LINE 0.80, BRANCH 0.70, ratcheted on
  2026-08-27 from 0.70/0.60 after the assessment workflow measured 85.7% and 76.9%; those had been
  ratcheted on 2026-08-26 from 0.50/0.40 after Phase 3 measured 77.6% and 66.1%. `apps/scoring` gained its own
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
  is most of the remaining coverage gap. Phase 5 reaches them. `ProcessedEvent` left this list in
  Phase 3 and `RiskAssessment` left it in Phase 4.
- **Nothing verifies that the compose stack's Kafka topics match the AsyncAPI contract.** The
  one-shot `kafka-topics` service creates the five the design names, and it is a second place the
  topic names are written — `EventTopics` and `EventTopicsTests` hold the other. A drift between
  them would show up as a producer retrying `UNKNOWN_TOPIC_OR_PARTITION` behind a healthy stack,
  which is exactly the symptom that hid for three phases. Worth an assertion when Phase 7 touches
  the stack's observability.

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

## Required before v1 — carried forward, and not optional

Everything in this section is **committed work that must land before the Phase 10 release**, not a
wish list. A session that reaches Phase 10 with any of these open has not finished. Each one is here
because it was deliberately deferred with a reason, and a deferral nobody records becomes a thing
nobody does.

### 1. Real operator identity, so an alert can be given to a named analyst

**Status: not started. Required before v1. Owner: whichever phase reaches it first, and Phase 10
cannot ship without it.**

Today the console can release an alert and cannot give one to anybody. `assigneeId` is a UUID,
nothing resolves it to a person, and the screen says so rather than offering a control that cannot
work (Phase 6 gate, deviation 2). That is honest and it is still a hole in the core workflow of a
fraud-operations console.

What "done" means here, and every clause is binding:

- **The smallest architecturally correct identity flow, and no smaller.** A real lookup of real
  operators — the ones the seed already creates in `users` — reached through the API.
- **No hardcoded UUIDs anywhere**, in the console, in a fixture used by a shipped screen, or in a
  contract example that a reader would take for a real identifier. **No fake or placeholder users**
  invented to make a picker look populated: the operators that exist are the operators that appear.
- **Server-side authorization stays authoritative.** Role handling in the console is a
  user-experience affordance and never a security boundary (`.claude/rules/frontend.md`). Whatever
  the picker offers, the API decides — an assignment to somebody who may not hold alerts is refused
  by the server, and there is a test that proves it rather than a disabled button that implies it.
- **Contracts and API updated as needed**, in the same change as the producers, consumers, tests and
  docs (`CLAUDE.md`, "Follow the contracts"). If an operator lookup endpoint is the answer, it is
  paged and bounded like every other list endpoint.
- **Real assignment UX in the console**: a picker that resolves names, an assignee column that
  renders a person rather than a UUID, and the four states every data view owes — loading, empty,
  error-with-retry, and bounded.
- **Optimistic concurrency and conflict handling.** Alerts already carry a version and the API
  already answers `409`; the assignment path must use it and the console must handle a lost race by
  telling the operator what happened, not by overwriting silently.
- **Tests at every level that has one**: unit, API integration against real PostgreSQL, and a
  Playwright end-to-end journey that assigns an alert and sees it assigned.
- **Documentation**: the ADR that records the decision (a new number, allocated when it is written),
  the README's screen description, and the removal of the "not decided" copy from the screen and
  from this file.
- **Verified against the real stack**, not only against Testcontainers — the lesson of the three
  defects recorded above is that those are not the same system.

### 2. Two human-verification items that no session may mark complete

Neither of these can be done by an agent, and **neither may be reported as done, inferred from an
automated result, or quietly dropped from a gate.** They are listed as outstanding human tasks until
a person says otherwise:

- **A screen-reader pass.** axe finds roughly a third of real accessibility issues. Every
  accessibility check in this repository is automated, so the screen-reader pass has **not** happened
  and no automated run is evidence that it did.
- **A manual authenticated walkthrough of the console in a browser.** What was done instead on
  2026-08-29 is recorded under the Phase 6 gate: every screen's endpoints called directly with a real
  token, and the cross-origin path proven to a refused sign-in. Typing the demo operator's password
  into the form is a person's job. The remaining visual walk is a two-minute task for whoever holds
  the credential.

## Blockers and required user input

None. The two human-verification items above are not blockers — the build continues around them —
but they are also not things any session may tick off on a person's behalf.

## Next three actions

**Phase 7 is closed.** All six deliverables are merged as
[#70](https://github.com/la3679/sentinelflow/pull/70) to
[#73](https://github.com/la3679/sentinelflow/pull/73),
[#82](https://github.com/la3679/sentinelflow/pull/82) and
[#83](https://github.com/la3679/sentinelflow/pull/83), and all four gate criteria are met with
evidence and with their limits stated. **Phase 8 — security and quality hardening — is next**, and it
inherits four items this repository has already written down about itself.

1. **Triage the seven open Dependabot pull requests before starting new work**, because two of them
   are major bumps that will interact with everything Phase 8 touches: TypeScript 5.9 to 7.0
   ([#81](https://github.com/la3679/sentinelflow/pull/81)) and `@eslint/js` 9 to 10
   ([#78](https://github.com/la3679/sentinelflow/pull/78)), plus `lucide-react` 0.575 to 1.34
   ([#79](https://github.com/la3679/sentinelflow/pull/79)). The rest are grouped minor and patch
   updates. **Read the "Dependabot" section above first**: the lockfile job is gated on the actor, so
   a human reopening a pull request dispatches every workflow and skips the one that would fix the
   lockfile, reporting `skipping` — which reads like success. Each major gets its own review and its
   own CI run, as the four merged on 2026-08-26 did.
2. **The threat model, and the four holes this repository has already documented.** Phase 8's first
   deliverable is a STRIDE-style threat model, and it should be written against what is actually
   here rather than against a generic system — four entries in "Known issues and technical debt"
   above are inputs to it and each names its own fix: `POST /api/v1/transactions` is
   unauthenticated (ADR-0012 §5), a token cannot be revoked before it expires,
   `/actuator/prometheus` is published to the host, and the CSV export's formula-injection defence
   needs to be traceable to a test rather than asserted. The management port that is not published
   to the host is the concrete fix for the third and is the smallest of the four.
3. **The scanning and supply-chain half**: CodeQL, dependency review, container scan and secret scan
   in CI, an SBOM and release checksums, and least-privilege workflow permissions with pinned
   third-party actions. `security-scan.yml` already runs a secret scan and a dependency review on
   every pull request, so this is an extension of something that works rather than a new pipeline.

**One thing Phase 8 should not re-decide.** Alert rules exist now and none of them pages anybody,
because there is no Alertmanager and adding one would be a service with no recipient. That is a
stated limit rather than an omission, and it is recorded in
`infra/prometheus/rules/sentinelflow.yml`'s own header and in the Phase 7 gate table.

### Three things this session learned the hard way, worth carrying forward

- **A Testcontainers `stop()` is not a restart, and neither is `docker stop`.** `stop()` removes the
  container; the `start()` after it creates a different one on a different ephemeral host port with
  an empty log. Docker re-picks the host port on a plain `docker stop`/`docker start` too — checked
  with a throwaway container, `32768` before and `32769` after. `docker pause` is what keeps the
  address and the log, and it is why `KafkaContainerSupport` grew a pause rather than a stop.
- **Widening a redaction test one notch found four leaks at once.** Raising it from "the application's
  package and Spring Web" to `logging.level.root=DEBUG`, and driving four more paths, found Hibernate
  dumping whole entities, `AlertNoteRequest` printing an analyst's note through Spring's own
  `Read […]` line, and `TransactionResponse` being safe only because a framework log line truncates
  at 100 characters. **A test that excuses the loggers it cannot satisfy is testing its own
  exclusions.** The version on `main` pins nothing itself; what protects it is `application.yaml`,
  which means a deployment gets the same guarantee.
- **An absent Prometheus series and a zero look identical on a graph and completely different in a
  rule.** Writing the dead-letter alert rule found `sentinelflow_consumer_deadletter_total` and
  `sentinelflow_consumer_undeliverable_total` with no series at all on the running stack — the third
  time this has been found here, after the dashboards found five. **Register every series a closed
  label set can produce, at zero, in the constructor.** The rarest outcome is the one worth alerting
  on, so it is precisely the one that does not exist when the rule is written.

**One decision is still open and it is not the console's.** How an assignee's identifier resolves to
a person. Until it is made, the console can release an alert but cannot give one to anybody, and that
is stated on the screen rather than papered over. **It is no longer optional:** "Required before v1 —
carried forward" §1 fixes what "done" means for it, and Phase 10 cannot ship with it open.

**Three operator actions have no endpoint and no owner.** Reprocessing a dead-lettered event,
reviving a `FAILED` outbox row and rescoring a degraded assessment were each described in
`docs/operations/RUNBOOKS.md` as "Phase 5 work". Phase 5 shipped and its deliverable list never
contained any of them, and nothing in `docs/planning/IMPLEMENTATION_PLAN.md` allocates them now. The
runbooks were corrected to say so and to give the manual procedure. **This is a gap, not a decision**
— ADR-0005 §5 fixes what each would have to be when it is built, administrator-only and audited, and
whichever phase picks them up should say so rather than inheriting the assumption that one already
has.

### What an earlier session found by running the stack rather than the suites

Three defects, all invisible to a green build, recorded here because the lesson generalises: the
Testcontainers suites and the compose stack are not the same system, and only one of them is what a
demo runs on.

- **Nothing created the Kafka topics.** ADR-0006 §3 decided they are created explicitly and disabled
  auto-creation; the step in between was never built. Every service reported healthy and no message
  could be published. Fixed by a one-shot `kafka-topics` service the API waits for.
- **The scoring client negotiated HTTP/2 against an HTTP/1.1-only service.** The JDK's `HttpClient`
  defaults to `HTTP_2`, so every request carried `Upgrade: h2c`; uvicorn refused it, could not read
  the body, and answered 422. Every scoring call was rejected. Pinned to HTTP/1.1, with the test
  asserting the absence of the header rather than the presence of the setting.
- **Two PowerShell targets had never worked.** `Invoke-NativeCapture` took no working directory
  while two callers passed one, and a `(?m)^api$` match never matched because of the carriage return.

**If assessments are ever all degraded again**, check the scoring service's log for "Unsupported
upgrade request" beside each rejection before looking anywhere else. `docs/operations/RUNBOOKS.md`
Runbook 4 has the full sequence.

### Before resuming, note the local database is on the LOCAL profile

The evaluation run needed it: DEMO produces a holdout with three positives, below the floor. The
stack currently holds 20,707 generated transactions plus a handful posted by hand on 2026-08-29.
`make seed` is a no-op against it.

**Do not truncate `users` to shrink the profile.** An earlier version of this note told a future
session to truncate `users, user_roles, customers, accounts, merchants, transactions, outbox_events,
processed_events CASCADE` and reseed, and that instruction is what broke this database. `users` holds
the `system` principal, which **V1 inserts as reference data and the seed does not write** — the seed
writes the four demo operators and nothing else. Truncating it therefore deletes a row nothing puts
back, and the API now refuses to start against a database missing it (`ReferenceDataVerifier`).

To go back to a smaller profile, use `make reset-demo` and reseed, which recreates the database and
re-runs the migrations. If the tables must be cleared in place instead, leave `users` and `user_roles`
alone and truncate only `customers, accounts, merchants, transactions, outbox_events,
processed_events CASCADE`.

**The local demo database carries the scars of the h2c defect.** 7,260 transactions are `FAILED` and
13,455 assessments are `degraded`, because they were processed while every scoring call was being
refused. The code is correct now — assessments written since the fix are scored — but the
distribution in that database is not one anyone should read anything into. `make reset-demo` then
`make seed` gives a clean one; see the profile note above first.

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
