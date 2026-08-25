# ADR-0009 — Frontend stack: adopt Lovable's TanStack Start foundation, run it client-side

- **Status:** Accepted
- **Date:** 2026-08-25
- **Research:** R-2026-08-25-09 in [`docs/research/RESEARCH_LOG.md`](../research/RESEARCH_LOG.md)
- **Relates to:** [ADR-0001](0001-lovable-first-repository-creation.md)

## Context

The SentinelFlow build specification nominates **Vite + React Router + Material UI** for the
frontend, while simultaneously requiring that the Lovable-generated application be the frontend
foundation and that Lovable two-way sync be preserved.

**Lovable currently generates new applications on TanStack Start.** This is Lovable's current
default generation stack for new projects — not a template that was mis-selected, and not
something a differently worded prompt would have changed. Lovable's project-creation API exposes
no tech-stack selector, and the generated project is wired to Lovable's own
`@lovable.dev/vite-tanstack-config` package, which supplies the TanStack Start, Nitro, Tailwind,
path-alias, and env-injection plugins as a single managed unit.

What Lovable generated:

| Concern         | Generated                                       | Specification nominated           |
| --------------- | ----------------------------------------------- | --------------------------------- |
| Build tool      | **Vite 8.1.5**                                  | Vite — match                      |
| Framework       | TanStack Start 1.168 (React 19.2, SSR-capable)  | plain React SPA                   |
| Router          | TanStack Router 1.170 (file-based, type-safe)   | React Router                      |
| Components      | Tailwind CSS 4.2 + shadcn/ui (Radix primitives) | Material UI                       |
| Data access     | Redux Toolkit 2.12 + RTK Query                  | RTK Query — match                 |
| Charts          | Recharts 2.15                                   | Recharts — match                  |
| Forms           | React Hook Form 7.71 + Zod 3.24                 | RHF + Zod — match                 |
| Package manager | Bun, single `bun.lock`                          | one manager, one lockfile — match |

Six of the nine concerns already match the specification exactly. The three that differ are
properties of Lovable's generation stack, and because sync is **two-way on a single branch**,
every future Lovable design session regenerates output against that same stack.

## Decision

**Adopt the generated TanStack Start foundation**, with one correction and one addition:

1. **Framework: TanStack Start — retained.** Migrating off it would mean rewriting every generated
   file and then fighting the tool on every subsequent design session, or abandoning Lovable —
   which [ADR-0001](0001-lovable-first-repository-creation.md) establishes we cannot do without
   also abandoning the repository.
2. **Router: TanStack Router — retained.** Type-safe and file-based. The requirement behind the
   "React Router" nomination is client-side routing with deep-linkable routes, which TanStack
   Router satisfies. `src/routeTree.gen.ts` is generated, is committed, and must never be
   hand-edited.
3. **Components: Tailwind + shadcn/ui (Radix) — retained.** Radix primitives supply the focus
   management, keyboard interaction, and ARIA semantics the WCAG 2.2 AA target requires; they are
   MIT licensed with no paid tier, satisfying the "free/community components only" constraint that
   motivated the Material UI nomination in the first place.
4. **Correction — configure client-oriented SPA behaviour.** The generated `vite.config.ts`
   points TanStack Start at an SSR server entry (`src/server.ts`) built through Nitro. SentinelFlow's
   application backend is Spring Boot; a second server runtime in front of it would add a
   server-side attack surface, a second deployment artifact, and a component the threat model
   would have to cover — with no benefit to an authenticated internal operations console.
   The console is therefore configured to render client-side and ship as static assets that the
   web container serves directly. This keeps the architecture diagram honest: exactly one
   application backend, which is Spring Boot.
5. **Addition — the specification's testing stack is adopted in full**, because Lovable generated
   none of it: Vitest + React Testing Library for unit and component tests, Playwright for
   end-to-end, and axe for accessibility. Added in Phase 1.

## Alternatives considered

- **Migrate to Vite SPA + React Router + Material UI as specified.** Rejected: discards the
  generated foundation, permanently breaks the Lovable workflow ADR-0001 depends on, and buys no
  accessibility or licensing improvement over Radix.
- **Keep the Nitro SSR server.** Rejected: adds an unneeded second runtime to secure, monitor, and
  document, and contradicts the "no technology without demonstrated need" rule.

## Consequences

**Positive** — Lovable stays usable for the whole life of the project. The component layer is
accessible and permissively licensed. The web tier is a static bundle, so its container is a plain
static file server running as non-root.

**Negative** — a deliberate, documented deviation from the specification's Vite-SPA, React Router,
and Material UI nominations. Anyone reading the specification alongside the code needs this ADR;
it is linked from the README technology table and from `docs/frontend/DESIGN_SYSTEM.md`.

**Constraint** — `@lovable.dev/vite-tanstack-config` owns the Vite plugin set. Plugins it already
supplies must not be added manually or the build breaks with duplicates. Vite configuration is
extended only through that package's `defineConfig` options.
