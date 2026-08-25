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
