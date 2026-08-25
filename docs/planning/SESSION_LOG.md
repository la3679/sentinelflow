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
