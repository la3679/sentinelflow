# ADR-0001 — Lovable creates the GitHub repository, not `gh`

- **Status:** Accepted
- **Date:** 2026-08-25
- **Research:** [R-2026-08-25-08](../research/RESEARCH_LOG.md)

## Context

SentinelFlow's operations console is designed and iterated in Lovable, and the rest of the
platform is built locally. Both need to write to the same repository.

Lovable's GitHub integration **creates a new repository when a project is connected and cannot
import an existing repository**. It synchronises **one branch at a time** — by default the
repository's default branch — and the sync is two-way.

If we created `la3679/sentinelflow` with `gh repo create` first, Lovable could never be connected
to it. We would be left choosing between abandoning Lovable or abandoning the repository history.

## Decision

1. **Lovable creates the repository first.** The Lovable project is built, then connected to the
   `la3679` GitHub account, and Lovable creates `la3679/sentinelflow`.
2. The repository is made **public only after** the generated files are scanned for secrets.
3. The Lovable-created initial commit is **preserved**, never squashed away or rewritten.
4. The repository is **cloned locally** and that clone is the _sole_ implementation workspace.
   Every subsequent build, test, branch, commit, and push happens from its root.
5. The repository is **never renamed, transferred, or deleted**. Transfer and deletion break
   Lovable sync irrecoverably; rename is tracked automatically but offers no benefit worth the risk.
6. **Strict `main` protection is enabled only after the initial Lovable phase.** Lovable responds
   to a rejected protected-branch push by silently diverting the change to a backup branch, which
   would produce confusing orphaned work. Afterwards, Lovable is pointed at a dedicated
   `design/lovable-*` branch and its output is merged through pull requests.
7. **Lovable and local development never edit the same branch concurrently.**

## Consequences

**Positive** — Lovable sync keeps working for the whole life of the project; the design tool and
the engineering workflow share one source of truth; no history rewrite is ever needed.

**Negative** — repository naming and creation order are constrained by a third-party tool, and
`main` is briefly unprotected during Phase 0. That window closes at the end of Phase 1, before any
external contribution is possible, and the repository is a single-maintainer portfolio project
throughout.

**Neutral** — GitHub becomes the source of truth once connected. Local state must be pushed before
any Lovable session begins.
