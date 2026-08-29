# Frontend rules — `apps/web`

Binding for every change under `apps/web`. TanStack Start + React 19 +
TypeScript, Tailwind 4 and shadcn/ui, Redux Toolkit with RTK Query, Recharts,
React Hook Form with Zod (ADR-0009). Bun is the only package manager.

The console renders **client-side**. Do not reintroduce the Nitro SSR server:
Spring Boot is the only application backend, and a second server runtime is
another deployment artifact and another thing the threat model has to cover.

## TypeScript

`strict` is on, along with `noUncheckedIndexedAccess` and
`exactOptionalPropertyTypes`. No casual `any`. An unavoidable one carries a
comment saying why. `as` casts are a smell — narrow with a type guard instead.

## State and data

- **RTK Query owns server state.** Do not add a second data-fetching library.
- **Redux owns client state that outlives a component.** Component state owns
  everything else. Not every value belongs in a store.
- **`src/mocks/` is gone and does not come back.** Every screen reads the API
  through `src/api/transport.ts`. Where the API cannot answer something, the
  screen says so and names the phase that measures it — a fixture added to make
  a screen look finished is the thing this rule exists to stop.
- **A screen stays current by polling, not by streaming** (ADR-0015). The cache
  re-reads on focus and on reconnect; two screens add an interval, each a named
  constant beside its query, applied through `refreshWhile` so that a feed
  nobody is watching does not poll. A third interval is a reason to reopen
  ADR-0015 rather than to add one.
- No browser storage for session or authorization state. There is a test that
  enforces this.

## Components

- Small and focused. A component that renders, fetches, transforms, and decides
  is four things.
- **No business logic in a component.** Risk rules, thresholds, and state
  transitions belong to the API.
- Role handling (`ANALYST`, `ADMINISTRATOR`, `AUDITOR`) is a user-experience
  affordance. **It is never a security boundary.** Disabling a control does not
  authorize anything; the server does that.

## Design tokens

Every colour, spacing and radius value comes from the tokens in
`src/styles.css`. No hardcoded colour utilities in components.

Amber, orange and red are reserved for genuine risk or operational severity.
Green is never decoration — in a fraud console it reads as "safe" or "approved",
which is a claim the UI must not make accidentally.

One accessible dark theme, done well, rather than a half-finished pair.

## Accessibility — WCAG 2.2 AA

Not optional, and not satisfied by an automated pass alone:

- Semantic landmarks and correct heading order
- Full keyboard operation, with a visible focus indicator on every control
- Status conveyed by **icon and text**, never colour alone
- Proper table semantics, and dialog focus management
- Accessible validation errors, associated with their field
- `prefers-reduced-motion` respected
- Adequate target sizes

axe runs in Playwright across every route at two viewports. **axe finds roughly
a third of real issues** — it is a floor, not a ceiling, and it is not evidence
that a screen reader works.

## Every data view

Loading, empty, error-with-retry, and paged or bounded. All four, every time.

**No dead controls.** A visible control either works against the current data
layer or is visibly marked as a documented future feature.

## Copy

Never state a performance, accuracy, or false-positive-reduction figure in the
UI. Every screen makes it discoverable that the data is synthetic and the
project is independent.

## Tests

- Vitest and React Testing Library for units. Query by role and accessible name,
  not by test id — that way the test fails when the accessible name breaks.
- Playwright for behaviour that needs a real browser: contrast, focus
  visibility, layout, and deep links.
- `bun run verify` before pushing.
