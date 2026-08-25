# Lovable and GitHub workflow

How this repository came to exist, how it stays connected, and what changed in Phase 1.

## Repository identity

| Field             | Value                                                               |
| ----------------- | ------------------------------------------------------------------- |
| Repository        | <https://github.com/la3679/sentinelflow>                            |
| Created by        | Lovable's GitHub integration, 2026-08-25                            |
| Default branch    | `main`                                                              |
| Visibility        | Public since 2026-08-25, after gitleaks passed over history         |
| Licence           | Apache-2.0                                                          |
| Lovable workspace | `Love's Lovable` (`sPLbx3W6voC6jPB4kPG6`)                           |
| Lovable project   | `SentinelFlow` (`e1341a35-a595-4af4-b0a5-c158ba286897`)             |
| Lovable editor    | <https://lovable.dev/projects/e1341a35-a595-4af4-b0a5-c158ba286897> |

**Never rename, transfer, or delete this repository.** Transfer and deletion break the Lovable
connection irrecoverably; renaming is tracked automatically but offers no benefit worth the risk.
Binding under [ADR-0001](../adr/0001-lovable-first-repository-creation.md).

## Why Lovable created the repository

Lovable's GitHub integration **creates a new repository when a project is connected and cannot
import an existing one**. Creating `la3679/sentinelflow` with `gh repo create` first would have
made connecting Lovable impossible. Hence the ordering: Lovable project, then connection, then
repository, then clone.

Full reasoning in [ADR-0001](../adr/0001-lovable-first-repository-creation.md); the underlying
research is R-2026-08-25-08 in [`RESEARCH_LOG.md`](../research/RESEARCH_LOG.md).

## Provenance — verified, not assumed

| Commit                | Author                          | Meaning                                                           |
| --------------------- | ------------------------------- | ----------------------------------------------------------------- |
| `0f401e5`             | `Lovable <noreply@lovable.dev>` | root commit, `template: tanstack_start_ts_current`                |
| `1bcddae` … `11294e7` | `gpt-engineer-app[bot]`         | five generation commits                                           |
| `afbd56d`             | `lovable <noreply@lovable.dev>` | `Add project README` — the branch point for all SentinelFlow work |

Lovable's original commit is still the root of this history. Nothing was squashed or rewritten,
and nothing ever should be — a rewrite corrupts Lovable's side of the sync as well as GitHub's.

## Synchronisation rules

- **GitHub is the source of truth** once the connection exists.
- Lovable syncs **one branch at a time**, by default the repository default branch.
- **Lovable and Claude never edit the same branch concurrently.**
- Before a Lovable session: fetch, ensure a clean tree, push everything, and record the branch.
- After a Lovable session: fetch, review **every** diff for accessibility, security, API
  boundaries, duplicate dependencies, broken types and dead mock code, run the frontend build and
  tests, then merge through a pull request.
- **A rendering preview is not evidence of correctness.** Lovable output is reviewed like any
  other contribution.

## Branch protection

`main` is protected by a ruleset that requires pull requests and passing checks
(see [`BRANCH_PROTECTION.md`](BRANCH_PROTECTION.md)).

This was deliberately enabled **after** the initial Lovable phase. Lovable responds to a rejected
protected-branch push by silently diverting the change to a backup branch rather than failing
outright, which would produce confusing orphaned work if it happened during generation.

## What changed in Phase 1 — Lovable generation is retired

Phase 1 moved the console from the repository root to `apps/web/`, so that the Spring Boot and
FastAPI services had a coherent place to live
([ADR-0002](../adr/0002-monorepo-and-service-boundaries.md)).

**Lovable has no documented support for an application outside the repository root.** Its GitHub
integration synchronises the repository as a whole, and its generation stack assumes the
application is at the top level. Moving the console therefore ends Lovable's ability to regenerate
or preview this project.

This is stated plainly rather than left to be discovered:

| Capability                                     | Status after Phase 1                                 |
| ---------------------------------------------- | ---------------------------------------------------- |
| Repository connection                          | **intact** — never rename, transfer, or delete       |
| Git history and provenance                     | **intact** — Lovable's root commit preserved         |
| Lovable regenerating or previewing the console | **no longer available** — the app is not at the root |

The cost was accepted deliberately. Lovable's role — creating the repository and delivering a
reviewed frontend foundation — was complete at the end of Phase 0. Phase 6 develops the console
in the local clone, wired to the real API: that is engineering work against a live backend, not
generation work.

### If a future design session is wanted

Two honest options, neither needed for v1:

1. **A dedicated `design/lovable-*` branch** with the console temporarily at the repository root,
   generated there, then transcribed back into `apps/web/` through a reviewed pull request. The
   directory move makes this merge awkward; treat the output as a design reference rather than a
   patch to merge.
2. **A separate Lovable project used purely as a sketchpad**, whose output is read and
   re-implemented by hand. This keeps `la3679/sentinelflow` untouched and is the lower-risk route.

**Do not** move the console back to the repository root on `main` to restore generation. The
monorepo layout is binding under ADR-0002, and superseding it requires a new ADR.

## The working-directory invariant

All implementation happens inside the clone whose `origin` is
`https://github.com/la3679/sentinelflow.git`. Before editing:

```bash
git rev-parse --show-toplevel
git remote -v
```

Never initialise a second repository inside this one. Never build a parallel local project and
try to replace this history later. The `SessionStart` hook checks the origin automatically and
warns loudly when it is wrong — a safety net, not a substitute for looking.
