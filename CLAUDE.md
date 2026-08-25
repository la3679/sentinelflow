# CLAUDE.md — SentinelFlow operating instructions

Permanent instructions for every Claude session in this repository. Keep this file short.
Detailed rules live in [`.claude/rules/`](.claude/rules/): [`java.md`](.claude/rules/java.md),
[`python.md`](.claude/rules/python.md), [`frontend.md`](.claude/rules/frontend.md), and
[`workflow.md`](.claude/rules/workflow.md). Read the one that covers what you are changing.

## What SentinelFlow is

An independent, educational, open-source portfolio project: an event-driven transaction-risk and
fraud-operations demo built with Spring Boot, Kafka, FastAPI, scikit-learn, React, PostgreSQL,
and OpenTelemetry. It runs entirely on **synthetic data**.

It is **not** an official product of any company, not a real bank system, not a regulatory
compliance product, and not a production fraud-decision engine. Never describe it as one, and
never reconstruct any employer's proprietary code, schemas, fraud rules, or metrics.

## Start every session with this

1. Run `git rev-parse --show-toplevel` and confirm you are inside this clone.
2. Run `git remote -v` and confirm `origin` is `https://github.com/la3679/sentinelflow.git`.
   **Stop** if it is not — do not work in a temporary folder or a second clone.
3. Read [`PROJECT_STATE.md`](PROJECT_STATE.md) completely. It is the authoritative resume file.
4. Run `git status --short --branch`, `git fetch`, and compare local and remote HEAD.
5. Check open PR and CI state (`gh pr list`, `gh run list --limit 5`).
6. Read the current phase in [`docs/planning/IMPLEMENTATION_PLAN.md`](docs/planning/IMPLEMENTATION_PLAN.md)
   and any ADR it references.
7. Continue from **"Next three actions"** in `PROJECT_STATE.md`.

Never redo a completed item without evidence that it is broken. If the repository contradicts
`PROJECT_STATE.md`, trust Git and the files, fix the state document, and note the discrepancy in
[`docs/planning/SESSION_LOG.md`](docs/planning/SESSION_LOG.md). Never delete unexpected work.

## Non-negotiable rules

- **Follow the contracts.** `contracts/openapi/`, `contracts/asyncapi/`, and `contracts/schemas/`
  are authoritative. Changing a contract means updating producers, consumers, tests, and docs in
  the same change.
- **Follow the ADRs.** A decision recorded in `docs/adr/` is binding until superseded by a new
  ADR. Do not quietly re-decide.
- **Small atomic commits, pushed regularly.** One understandable change per commit, with its
  tests. Conventional Commits. Push after every two to four commits and at every phase boundary.
  Never claim "pushed" until you have verified the remote SHA.
- **Tests and docs ship with behaviour.** A feature is not done without them.
- **No secrets, ever.** No tokens, keys, `.env` files, passwords, or real financial data in the
  repository, logs, metrics labels, event payloads, or commit messages.
- **No invented numbers.** Coverage, latency, throughput, false-positive rates, and test counts
  are only ever reported from an actual run, with the command and date recorded.
- **Synthetic data only.** No real or realistic PII — no real names, addresses, national IDs, or
  card numbers.

## Checkpoint protocol

Context percentage governs the work you may start. See §27 of the build prompt for the full
policy; the short form is:

| Context used | Behaviour                                                                                                               |
| ------------ | ----------------------------------------------------------------------------------------------------------------------- |
| 0–69%        | Work normally, committing coherent units.                                                                               |
| 70–79%       | Finish the current small unit. Do not start a large multi-file feature. Test, commit, push, refresh `PROJECT_STATE.md`. |
| 80–84%       | **Mandatory checkpoint.** Stop new feature work and run the full checkpoint below.                                      |
| ≥85%         | **Emergency checkpoint.** No implementation. Save state, commit what is safe, push, end or compact.                     |
| unknown      | Checkpoint at every phase boundary, before and after Lovable use, before long builds, and at least once per session.    |

**Checkpoint steps:** stop new work → inspect `git status` and both diffs → `git diff --check` →
run the smallest relevant format/lint/type/test checks → update `PROJECT_STATE.md` with real
progress and exact next actions → append to `docs/planning/SESSION_LOG.md` → commit with a
correct Conventional Commit message → push → verify the remote SHA and CI → confirm no secret or
bulk generated data was staged → only then compact or end.

Run `scripts/claude/checkpoint` (or `.\scripts\claude\checkpoint.ps1`) to gather the Git and CI
facts. It never infers semantic progress — you must write that yourself.

Four hooks assist: `SessionStart` injects verified Git state, `Stop` asks once per session for a
checkpoint when work is uncommitted and this file is stale, and `PreCompact` and `SessionEnd`
write git-ignored snapshots. They are read-only and never commit or push anything. See
[`docs/development/CLAUDE_CODE_SETUP.md`](docs/development/CLAUDE_CODE_SETUP.md) to debug or
disable them.

## Working with Lovable

Lovable created this repository and syncs **one branch at a time**. Lovable and Claude must never
edit the same branch concurrently.

Before a Lovable session: fetch, ensure a clean tree, push everything, and point Lovable at a
`design/lovable-*` branch. After: fetch, review **every** diff for accessibility, security, API
boundaries, duplicate dependencies, broken types, and dead mock code, run the frontend build and
tests, then merge through a PR. A rendering preview is not evidence of correctness.

Never rename, transfer, or delete the repository.

## Standard commands

```text
make bootstrap        # verify prerequisites, generate safe local config
make up / make down   # start / stop the local stack
make seed             # deterministic demo data
make replay           # replay the default synthetic scenario
make test             # all standard suites
make test-integration # Testcontainers PostgreSQL + Kafka
make test-e2e         # Playwright
make lint / format-check / security / smoke / docs-check
```

## Toolchain

Java 25 (Temurin) · Spring Boot 4.1.1 · Python 3.13 via `uv` · Node.js 24 · PostgreSQL 18 ·
Kafka 4.2.1 (KRaft). Versions and their justification live in
[`docs/research/RESEARCH_LOG.md`](docs/research/RESEARCH_LOG.md). Do not bump a core version
without a research entry and, where consequential, an ADR.

## Prohibited shortcuts

No giant code dumps. No claiming work is complete before running it. No H2 standing in for
PostgreSQL. No mocking Kafka and PostgreSQL away in all integration tests. No floating-point
money. No unbounded list, export, or replay endpoints. No broad `any` in TypeScript. No swallowed
exceptions. No JPA entities on API contracts. No business logic in controllers or components. No
placeholder badges, dummy URLs, or dead controls in a release. No rewriting or fabricating Git
history.
