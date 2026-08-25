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
| Last updated UTC     | 2026-08-25T18:40Z                                                                                                                                |
| Updated by           | Claude                                                                                                                                           |
| Overall status       | active — Phase 0 complete, Phase 1 not started                                                                                                   |
| Current phase        | Phase 0 — research, product baseline, Lovable, and repository                                                                                    |
| Current task         | Phase 0 handoff delivered; stop before Phase 1                                                                                                   |
| GitHub repository    | <https://github.com/la3679/sentinelflow>                                                                                                         |
| Visibility           | see "Repository visibility" below                                                                                                                |
| Default branch       | `main`                                                                                                                                           |
| Working branch       | `chore/phase-0-foundation`                                                                                                                       |
| Local clone verified | **yes**                                                                                                                                          |
| Local workspace      | a `sentinelflow/` folder inside the user's Documents workspace. The absolute path is recorded in the git-ignored `.claude/runtime/worktree.json` |
| Lovable sync branch  | `main` (Lovable syncs one branch at a time)                                                                                                      |

### Lovable project identity

| Field             | Value                                                                                                                                         |
| ----------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| Workspace         | `Love's Lovable` (`sPLbx3W6voC6jPB4kPG6`), plan `pro`                                                                                         |
| Project           | `SentinelFlow`                                                                                                                                |
| Project ID        | `e1341a35-a595-4af4-b0a5-c158ba286897`                                                                                                        |
| Editor            | <https://lovable.dev/projects/e1341a35-a595-4af4-b0a5-c158ba286897>                                                                           |
| GitHub connection | active, verified 2026-08-25                                                                                                                   |
| Generation stack  | Lovable's current default: Vite 8.1.5 + TanStack Start 1.168 + TanStack Router 1.170 + React 19.2 + Tailwind 4.2 + shadcn/ui + Bun (ADR-0009) |

### Repository provenance — verified, not assumed

| Commit                | Author                          | Meaning                                                           |
| --------------------- | ------------------------------- | ----------------------------------------------------------------- |
| `0f401e5`             | `Lovable <noreply@lovable.dev>` | root commit, `template: tanstack_start_ts_current-b3e81c491308`   |
| `1bcddae` … `11294e7` | `gpt-engineer-app[bot]`         | five generation commits                                           |
| `afbd56d`             | `lovable <noreply@lovable.dev>` | `Add project README` — the branch point for all SentinelFlow work |

Lovable's original commit and full history are preserved. Nothing was squashed or rewritten.

## Product status by phase

- [x] **Phase 0 — research and Lovable/GitHub bootstrap**
- [ ] Phase 1 — repository and developer foundation
- [ ] Phase 2 — contracts, domain, and database
- [ ] Phase 3 — ingestion, outbox, and Kafka
- [ ] Phase 4 — synthetic data and scoring
- [ ] Phase 5 — alerts and investigations
- [ ] Phase 6 — operations frontend
- [ ] Phase 7 — observability and resilience
- [ ] Phase 8 — security and quality hardening
- [ ] Phase 9 — performance and documentation
- [ ] Phase 10 — release

## Completed this session

**Research gate** — ten primary-source entries in `docs/research/RESEARCH_LOG.md`. Versions
locked: Java 25 LTS / Spring Boot 4.1.1 / Python 3.13 via uv / Node 24 / PostgreSQL 18.6 /
Kafka 4.2.1 KRaft / Prometheus v3.14.0 / Grafana 13.2.0 / OTel Collector 0.159.0.

**Toolchain** — Temurin `jdk-25.0.4.1+1` provisioned to `%USERPROFILE%\.jdks`, SHA-256 verified
against the Adoptium API. Bun 1.4.0 installed (the project's single package manager).

**Repository** — Lovable created `la3679/sentinelflow`; verified by owner, provenance, branch, and
clone URL, then cloned and verified as the sole implementation workspace.

**Foundation audit** — `docs/frontend/FOUNDATION_AUDIT.md`. 23 checks; 18 passed as generated,
3 authentication-scope findings and 1 dead dependency corrected, 1 check unblocked by adding the
missing test tooling.

**Corrections** — eight commits, listed under "Git state".

## Acceptance criteria status — Phase 0 gate

| Criterion                                       | Status   | Evidence                                         |
| ----------------------------------------------- | -------- | ------------------------------------------------ |
| Research gate complete before implementation    | **pass** | `docs/research/RESEARCH_LOG.md`, 10 entries      |
| Repository created by Lovable and sync verified | **pass** | provenance table above                           |
| Clone root, `origin`, branch, HEAD verified     | **pass** | `git rev-parse --show-toplevel`, `git remote -v` |
| Lovable initial commit preserved                | **pass** | `0f401e5` is still the root commit               |
| No secrets present                              | **pass** | gitleaks over full history, 0 leaks              |
| Initial build succeeds                          | **pass** | `bun run build` exit 0                           |
| State file contains exact repo/branch/SHA       | **pass** | this file                                        |

## Test and verification evidence

All commands run from the repository root on 2026-08-25.

| Command                   | Result            | Notes                                                  |
| ------------------------- | ----------------- | ------------------------------------------------------ |
| `bunx tsc --noEmit`       | **PASS** (exit 0) | confirms Lovable's typecheck claim independently       |
| `bunx eslint .`           | **PASS** (exit 0) | 0 errors, 7 warnings (vendored shadcn/ui fast-refresh) |
| `bunx prettier --check .` | **PASS**          | after `.gitattributes` normalisation                   |
| `bun run test`            | **PASS**          | 24/24 across 5 files                                   |
| `bun run test:coverage`   | 40.4% lines       | routes are covered by the browser tests instead        |
| `bun run test:e2e`        | **PASS**          | 58/58 — 29 desktop + 29 tablet                         |
| axe WCAG 2.1 A/AA         | **PASS**          | 0 violations on all 8 routes, both viewports           |
| `bun run build`           | **PASS** (exit 0) | static output, prerendered 1 shell                     |
| gitleaks full history     | **PASS**          | 0 leaks                                                |

**No coverage, latency, throughput, or false-positive figure has been claimed beyond what is
listed above, and every figure here came from an actual run.**

## Git state

- Branch: `chore/phase-0-foundation`, pushed and tracking `origin`.
- Working tree: clean.
- Lovable synchronization: connected; Lovable syncs `main`, which this branch has not touched.

Commits this session, oldest first:

| SHA       | Message                                                                    |
| --------- | -------------------------------------------------------------------------- |
| `c57dcee` | `chore(repo): normalize line endings via .gitattributes`                   |
| `bfaf2d0` | `fix(web): remove persisted demo session and route gate`                   |
| `f19cd99` | `refactor(web): render the console client-side`                            |
| `45ee061` | `chore(web): drop unused TanStack Query dependency`                        |
| `83c9610` | `chore(web): name the package and add a reproducible script surface`       |
| `a59001a` | `test(web): add Vitest, Testing Library, and axe with a scope-guard suite` |
| `2f8ab74` | `test(e2e): add Playwright browser, accessibility, and responsive checks`  |
| `ee8bc40` | `ci(web): add quality, browser, and security workflows`                    |
| `557a393` | `chore(repo): add Apache-2.0 licence and NOTICE`                           |
| `938e267` | `docs: record the research gate, product baseline, and Phase 0 ADRs`       |
| `c7fc6a6` | `docs(web): record the foundation audit and its evidence`                  |

## Architecture and decisions

**Accepted ADRs**

| ADR  | Decision                                                                                            |
| ---- | --------------------------------------------------------------------------------------------------- |
| 0001 | Lovable creates the GitHub repository; never rename, transfer, or delete it                         |
| 0003 | Java 25 LTS + Spring Boot 4.1.1; dependency versions inherited from the BOM                         |
| 0004 | Python 3.13 via uv for `apps/scoring`                                                               |
| 0009 | Adopt Lovable's TanStack Start foundation; render client-side so Spring Boot stays the sole backend |

**Still needing an ADR:** 0002 monorepo boundaries · 0005 Kafka outbox and delivery semantics ·
0006 event schema and versioning · 0007 Flyway and money representation · 0008 scoring-service
boundary · 0010 model and evaluation choice · 0011 SSE versus WebSockets · 0012 authentication ·
0013 observability · 0014 deployment strategy.

**Contracts:** none yet — `contracts/` is created in Phase 2.

## Known issues and technical debt

- **Node 22.19.0 on the reference machine passed its LTS end date (2026-07-28).** `engines`
  requires Node 24. Bun runs the frontend so this has not blocked anything, but local Node should
  be upgraded.
- **Default `JAVA_HOME` still points at JDK 17.** JDK 25 is installed but is not the machine
  default; `make bootstrap` must select it explicitly in Phase 1.
- **The monorepo layout does not exist yet.** The repository root is still the web application.
  Phase 1 moves it to `apps/web/` and adds `apps/api/` and `apps/scoring/`.
- **Google Fonts is loaded from a remote host.** Self-host in Phase 6 so the local-first stack has
  no external runtime dependency.
- **`noUnusedLocals` / `noUnusedParameters` are `false`** in `tsconfig.json`. Enable in Phase 1.
- **Coverage thresholds are not yet enforced.** Set in Phase 1 once a baseline exists, rather than
  writing meaningless tests to hit a number now.
- **Screen-reader behaviour is unverified.** axe is not a substitute for a manual NVDA/VoiceOver
  pass. Phase 6.
- **`main` has no branch protection.** Deliberate, per ADR-0001: Lovable diverts rejected pushes
  to a backup branch, so strict rules go on at the end of Phase 1.

## Blockers and required user input

None.

## Next three actions

1. Open the pull request from `chore/phase-0-foundation` into `main`, confirm both CI workflows
   pass on the remote, and merge preserving history (merge commit, not squash).
2. Begin Phase 1: restructure to the monorepo layout (`apps/web/`, `apps/api/`, `apps/scoring/`),
   add `.editorconfig`, `.env.example`, the Maven Wrapper, the `uv` project, and `compose.yaml`
   with PostgreSQL 18.6, Kafka 4.2.1 KRaft, Prometheus, Grafana, and the OTel Collector.
3. Add ADR-0002 for monorepo and service boundaries, and enable `main` branch protection once the
   CI check names exist.

## Session startup commands

```text
cd <clone root>
git rev-parse --show-toplevel
git remote -v
git branch --show-current
git status --short --branch
git fetch --all --prune
git log --oneline -10
gh pr list
gh run list --limit 5
bun install --frozen-lockfile
bun run verify
```

## Safe resume prompt

> Read CLAUDE.md and PROJECT_STATE.md, verify Git and GitHub state, then continue the first
> incomplete action without repeating completed work.
