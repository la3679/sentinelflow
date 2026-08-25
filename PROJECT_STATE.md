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
| Last updated UTC     | 2026-08-25T23:10Z                                                                                                                                |
| Updated by           | Claude                                                                                                                                           |
| Overall status       | active — Phase 1 merged to `main`; Phase 2 not started                                                                                           |
| Current phase        | Phase 1 — complete and merged via PR #2                                                                                                          |
| Current task         | Phase 2 — contracts, domain, and database                                                                                                        |
| GitHub repository    | <https://github.com/la3679/sentinelflow>                                                                                                         |
| Visibility           | **PUBLIC** since 2026-08-25, after both scans passed                                                                                             |
| Default branch       | `main` — **protected** since 2026-08-25 (ruleset `main protection`, id `21493410`)                                                               |
| Working branch       | `main` — Phase 2 branches from here                                                                                                              |
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
- [ ] Phase 2 — contracts, domain, and database
- [ ] Phase 3 — ingestion, outbox, and Kafka
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

Every figure below came from a run on **2026-08-25**. Nothing here is estimated.

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

**Contracts:** none yet — `contracts/` is created in Phase 2 with its first schema.

## Known issues and technical debt

- **Node 22.19.0 on the reference machine** passed its LTS end date (2026-07-28). `engines`
  requires Node 24. Bun runs everything, so nothing is blocked, but local Node should be upgraded.
- **Default `JAVA_HOME` points at JDK 17.** JDK 25 is installed at `%USERPROFILE%\.jdks`;
  `make bootstrap` warns rather than fails, because `make up` builds the API in Docker.
- **The published Temurin 25 image is one critical-patch build behind the local JDK.** Containers
  build on `25.0.4+7`, local `./mvnw` runs on `25.0.4.1+1`. Both are Java 25 LTS. Revisit when
  Adoptium publishes `25.0.4.1`.
- **`noUnusedLocals` / `noUnusedParameters` are still `false`** in `apps/web/tsconfig.json`.
- **Coverage thresholds are not enforced** in any component. Deliberate: a threshold set against a
  scaffold measures how much of a scaffold is exercised. Set them in Phases 2 and 4.
- **Google Fonts is loaded from a remote host.** Self-host in Phase 6 so the local-first stack has
  no external runtime dependency.
- **Screen-reader behaviour is unverified.** axe is not a substitute for a manual pass. Phase 6.
- **The console still renders from `src/mocks/`.** Replaced in Phase 6, once the API has endpoints.
- **CI is not path-filtered**, so every push runs every component. Deliberate (ADR-0002); revisit
  when runtime justifies job-level change detection.

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

1. **Write ADR-0006 (event schema and versioning) and ADR-0007 (Flyway and money representation)
   before any schema exists.** Both constrain everything Phases 2 and 3 build, and both are far
   cheaper to decide now than to reverse later.
2. **Create `contracts/`** with the OpenAPI baseline and the transaction and risk event schemas,
   plus CI validation of them. This is the first real content in that directory, which is why it
   has not existed until now.
3. **Add the first Flyway migrations and their Testcontainers PostgreSQL tests.** Real PostgreSQL,
   never H2. Set a JaCoCo threshold and a pytest `fail_under` once there is behaviour worth
   measuring.

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
