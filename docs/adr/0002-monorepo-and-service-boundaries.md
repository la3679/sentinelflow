# ADR-0002 — Monorepo layout and service boundaries

- **Status:** Accepted
- **Date:** 2026-08-25
- **Supersedes:** nothing
- **Related:** [ADR-0001](0001-lovable-first-repository-creation.md),
  [ADR-0009](0009-frontend-component-library.md)

## Context

SentinelFlow is three applications in three languages: a Spring Boot service that owns
transactions and alerts, a FastAPI service that owns risk scoring, and a React console.
They share a domain vocabulary, a set of event and API contracts, and one local stack.

Until Phase 1 the repository root **was** the React console — the shape Lovable generated. That
left no coherent place for the other two applications, and made "polyglot monorepo" a claim the
tree did not support.

Three structural questions had to be answered together, because the answers constrain each other:

1. One repository or three?
2. If one, where does each application live, and what owns the shared configuration?
3. What is each service actually responsible for?

## Decision

### 1. One repository

A single monorepo at `la3679/sentinelflow`.

The contracts between these services change together. A new field on a transaction event touches
the producer, the consumer, the schema, and the console in one logical change. Across three
repositories that is a four-pull-request dance with a window in which the system is inconsistent,
and no single commit a reviewer can look at. Here it is one commit and one CI run.

Three repositories would also triple the CI, dependency-automation, and release surface for a
project maintained by one person.

### 2. Layout

```text
apps/api/        Spring Boot - transactions, outbox, alerts, audit
apps/scoring/    FastAPI - features, inference, model registry
apps/web/        React console
contracts/       OpenAPI, AsyncAPI, JSON Schema - authoritative
infra/           Prometheus, Grafana, container configuration
scripts/         Developer, smoke, and maintenance scripts
docs/            ADRs, architecture, research, planning, operations
```

Each application owns its own build, its own dependency manifest, its own tests, and its own
Dockerfile. Nothing reaches across an `apps/` boundary at build time.

**The repository root is a Bun workspace.** This is a deliberate deviation from the tree sketched
in the build standards, which places the Node lockfile under `apps/web/`. Prettier formats the
Markdown, YAML and JSON of _every_ component, not just the console, so the tool has to be
installable from the root. A workspace root keeps exactly one Node lockfile in the repository —
which was the actual requirement — and puts repository-wide formatting where it belongs.

Empty directories are not created. `contracts/` appears in Phase 2 with its first schema.

### 3. Service boundaries

| Owns                                                  | Service        |
| ----------------------------------------------------- | -------------- |
| Transaction ingestion, idempotency, persistence       | `apps/api`     |
| The transactional outbox and Kafka publication        | `apps/api`     |
| Alert lifecycle, state transitions, assignment, audit | `apps/api`     |
| Authentication and authorization                      | `apps/api`     |
| Deterministic rule scoring                            | `apps/api`     |
| Feature engineering and feature versioning            | `apps/scoring` |
| Model inference, model registry, model metadata       | `apps/scoring` |
| Evaluation metrics and thresholds                     | `apps/scoring` |
| Presentation, navigation, accessibility               | `apps/web`     |

**Two services, not more.** The split is along a real seam: the Python scientific stack is where
the model has to live, and a JVM service is where transactional integrity and the outbox have to
live. Splitting further — a separate alert service, a separate ingestion service — would add
network calls and failure modes between things that share a transaction, purely for appearance.

**The API is the only backend the console talks to.** Scoring is reached by the API, never
directly by the browser. One authorization boundary, one audit trail, one place a rate limit can
be enforced. This is also why the console renders client-side (ADR-0009): a second server runtime
in front of Spring Boot would be a second deployment artifact and a second thing the threat model
has to cover, for no benefit.

**Rule scoring stays in the API; model scoring stays in scoring.** Rules are deterministic
business policy that must run inside the transaction. A model call is a network hop to a service
that can be slow or down, and the API must degrade to rules alone when it is.

### 4. CI is not path-filtered

Every component workflow runs on every push and pull request.

A workflow skipped by an `on: paths` filter produces **no check run at all**, and a required check
that never reports blocks a pull request permanently. The alternatives are job-level change
detection, which adds a third-party action and a layer of indirection, or leaving component checks
out of the ruleset, which means a broken build can merge.

The whole suite is a few minutes on a free public runner. Filtering becomes worth its complexity
when the runtime justifies it, not before.

## Alternatives considered

**Polyrepo.** Rejected: contract changes would span repositories with no atomic commit, and the
operational overhead triples for one maintainer.

**Keep the console at the repository root and nest the other two.** Rejected: it makes the
console structurally privileged over two applications of equal weight, and the layout stops
describing what the project is.

**A build tool over the whole monorepo — Nx, Bazel, Gradle multi-project.** Rejected: three
applications with no shared build artifacts do not need a build graph. Each toolchain is already
excellent at building its own language, and `make` is enough to name the commands.

**A shared domain library across `apps/api` and `apps/scoring`.** Rejected: it would be a
cross-language artifact for a handful of type definitions. `contracts/` is the shared vocabulary,
and code generated from a contract beats a hand-maintained library in two languages.

## Consequences

**Positive.** A contract change is one commit and one CI run. The tree describes the system.
Each application keeps an idiomatic build a specialist in that language would recognise. One
`docker compose up` starts everything.

**Negative.** The repository is larger to clone. Every CI run exercises every component, which
costs minutes that path filtering would save. A contributor interested in only the console still
sees the whole tree.

**Consequence for Lovable — the significant one.** Lovable has no documented support for an
application outside the repository root; its GitHub integration synchronises the repository as a
whole and its generation stack assumes the application is at the top level. Moving the console to
`apps/web/` therefore ends Lovable's ability to regenerate or preview this project.

That cost was accepted deliberately. Lovable's role — creating the repository and delivering the
reviewed frontend foundation — is complete, and ADR-0001 remains binding: the repository is never
renamed, transferred, or deleted, and the Lovable connection is left in place. Phase 6 develops
the console in this clone, wired to the real API, which is engineering work rather than generation
work. If a future design session is wanted, the honest options are a dedicated
`design/lovable-*` branch with the console temporarily at the root, or a separate Lovable project
used purely as a design sketchpad whose output is transcribed by hand. Neither is needed for v1.

**Revisit if:** CI runtime grows enough that path filtering is worth job-level change detection;
a third service earns its own deployable boundary with a measured reason; or Lovable gains
documented monorepo support, at which point the console's regeneration path can be restored.
