# Frontend foundation audit — Lovable Phase 0 output

**Audited:** 2026-08-25 · **Lovable project:** `e1341a35-a595-4af4-b0a5-c158ba286897` ·
**Auditor:** Claude (engineering lead)

The Phase 0 frontend was explicitly scoped as a **presentational foundation with deterministic
synthetic fixtures and no authentication**. This audit checks the generated code against that
scope. Findings are recorded here first; corrections land as separate reviewable commits so the
audit and the fixes are both visible in Git history.

Lovable's completion summary claimed the foundation was "verified end-to-end" including
"sign-in, session persistence across reloads" with "typecheck and lint clean". Those claims were
checked against the code rather than accepted.

**Status: closed.** Every finding below has been corrected in the commits listed at the end, and
every check has been re-run with its result recorded. Verification commands and outputs are in
[Evidence](#evidence).

## Verdict summary

| #   | Check                                                | Result                                          |
| --- | ---------------------------------------------------- | ----------------------------------------------- |
| 1   | No real authentication                               | **Pass**                                        |
| 2   | No credential validation                             | **Pass**                                        |
| 3   | No password handling                                 | **Pass**                                        |
| 4   | No tokens or auth cookies                            | **Pass**                                        |
| 5   | No authentication provider                           | **Pass**                                        |
| 6   | No persistent authenticated session                  | Fixed — `bfaf2d0`                               |
| 7   | No authenticated state in local/sessionStorage       | Fixed — `bfaf2d0`, regression-tested            |
| 8   | No route protection presented as a security boundary | Fixed — `bfaf2d0`, regression-tested            |
| 9   | No claim that the mock interface provides security   | **Pass**                                        |
| 10  | No Supabase                                          | **Pass**                                        |
| 11  | No database                                          | **Pass**                                        |
| 12  | No backend implementation                            | Fixed — `f19cd99`, static build only            |
| 13  | No cloud integration                                 | **Pass**                                        |
| 14  | No API credentials or secrets                        | **Pass**                                        |
| 15  | No real financial data                               | **Pass**                                        |
| 16  | Fixtures deterministic and synthetic                 | **Pass**                                        |
| 17  | RTK Query, not TanStack Query                        | Pass — dead TanStack Query removed in `45ee061` |
| 18  | Typed endpoints on an env-derived `/api/v1` base URL | **Pass**                                        |
| 19  | Mock layer separated and replaceable                 | **Pass**                                        |
| 20  | No unintended real network requests in mock mode     | Pass — verified in a real browser               |
| 21  | TypeScript strict mode                               | **Pass**                                        |
| 22  | Required screens render                              | Pass — verified rendering in a real browser     |
| 23  | Typecheck, lint, unit tests, build, a11y checks pass | Pass — tooling added in `a59001a` / `2f8ab74`   |

## Authentication-scope findings

### Passing — what Lovable got right

`src/routes/login.tsx` is genuinely presentational. There is **no password field**, no credential
comparison, and no token issuance. The form collects only a free-text operator ID and a role from
a `Select`, validated by Zod purely for input shape (3–40 characters, role must be one of the
three known values). The screen carries an explicit visible disclaimer:

> Demonstration sign-in only. There is no authentication, no credential check and no stored token.

and the role control is labelled:

> Roles change which controls are offered in the interface. They are not a security boundary.

`src/store/sessionSlice.ts` is consistent with that: the state holds `signedIn`, `operatorId`, and
`role` only, and its own doc comment reads "Mock-only session marker. No token, no credential, no
real authentication." The action is named `mockSignIn`. No auth provider, no Supabase client, no
Lovable Cloud auth, and no cookie handling appears anywhere in the tree.

### Finding A — session state is persisted to `sessionStorage` _(must fix)_

`src/store/session-bootstrap.tsx` writes the signed-in operator and role to
`window.sessionStorage` under the key `sentinelflow.demo-session`, and restores it on mount:

```ts
const STORAGE_KEY = "sentinelflow.demo-session";
window.sessionStorage.setItem(
  STORAGE_KEY,
  JSON.stringify({ operatorId: session.operatorId, role: session.role }),
);
```

This is the mechanism behind Lovable's "session persistence across reloads" claim. It directly
violates two prohibitions for this phase: **no persistent authenticated sessions**, and **no
storing of authenticated-user state in localStorage or sessionStorage**.

No credential or token is stored, so the security impact is low — but the prohibition is about not
building an authentication shape at all in this phase, and a persisted `signedIn` flag is exactly
that shape. The real authenticated session belongs to the Spring Boot API in Phase 5.

**Correction:** delete `session-bootstrap.tsx`, remove the `hydrateSession` reducer and the
`hydrated` flag, and keep the demo operator selection **in memory only**.

### Finding B — session-gated redirect behaves as a route guard _(must fix)_

`src/routes/login.tsx` redirects away from the sign-in screen whenever a restored session is
found:

```ts
useEffect(() => {
  if (session.hydrated && session.signedIn) void navigate({ to: "/" });
}, [session.hydrated, session.signedIn, navigate]);
```

Combined with Finding A this reproduces the observable behaviour of an authenticated app —
reload, skip the sign-in screen, land inside. That is route protection presented as a boundary,
which this phase prohibits.

**Correction:** remove the redirect effect. Once persistence is gone the demo entry action becomes
what it was scoped to be — a non-security navigation control into the prototype.

### Finding C — an SSR server entry is present _(fix as part of ADR-0009)_

`vite.config.ts` points TanStack Start at `src/server.ts` and builds it through Nitro with a
Cloudflare default target. This is Lovable's generated default, not an implemented backend — it
contains no application logic, no data access, and no endpoints. But it is a server runtime, and
SentinelFlow's backend must be Spring Boot alone.

**Correction:** configure client-side rendering so the console ships as static assets, per
[ADR-0009](../adr/0009-frontend-component-library.md).

## Data-layer findings

**RTK Query is correctly used.** `src/api/sentinelApi.ts` defines eleven typed endpoints —
overview, transactions list and detail, alerts list and detail, assign, transition, add note,
reports, model policy, and health — with proper `providesTags`/`invalidatesTags` cache
invalidation. Every URL is built from `API_BASE_URL`.

**The base URL is environment-derived**, exactly as required:

```ts
export const API_BASE_URL: string = import.meta.env["VITE_API_BASE_URL"] ?? "/api/v1";
export const USING_MOCK_DATA = true;
```

**The mock layer is cleanly separable.** `src/mocks/mockBaseQuery.ts` is a `BaseQueryFn` that
resolves request descriptors against in-memory fixtures and **never calls `fetch`**. Its own
header states the swap plan: replacing it with `fetchBaseQuery({ baseUrl: API_BASE_URL })` is the
only change needed once the Spring Boot API exists. Because RTK Query endpoints already emit real
`/api/v1` request descriptors — including method and body — the endpoint definitions themselves
need no change at cutover.

**Finding D — `@tanstack/react-query` was dead weight.** It was present in `package.json` at
`^5.101.1` and `src/router.tsx` did construct a `QueryClient` — but only to place it in the router
context, and nothing ever read that context value. No `QueryClientProvider` was mounted anywhere,
so any `useQuery` call would have thrown at runtime. A leftover from Lovable's base template.

_(An earlier draft of this audit called the dependency "unused" without qualification. It was
referenced in `router.tsx`; it was never functional.)_

**Correction:** remove it, so there is exactly one data-access library and no ambiguity about
which one application code should use.

**Finding E — one intentional external network request.** `src/routes/__root.tsx` loads IBM Plex
Sans and IBM Plex Mono from `fonts.googleapis.com`. This is a deliberate font load, not an API
call, so it does not violate "no unintended real network requests" — but it does mean the console
is not fully self-contained offline.

**Correction (low priority, Phase 6):** self-host the fonts so the local-first stack has no
external runtime dependency.

## Quality findings

**TypeScript strict mode is on, and then some.** `tsconfig.json` enables `strict: true` plus
`noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`, `noImplicitReturns`,
`noImplicitOverride`, `noPropertyAccessFromIndexSignature`, `noFallthroughCasesInSwitch`, and
`noUncheckedSideEffectImports`. This exceeds the requirement.

_Minor:_ `noUnusedLocals` and `noUnusedParameters` are both `false`. Worth enabling in Phase 1 so
dead code fails the build rather than accumulating.

**Finding F — no test tooling exists at all.** `package.json` contains no Vitest, no React Testing
Library, no Playwright, and no axe. There is also **no `typecheck` script** — only `dev`, `build`,
`build:dev`, `preview`, `lint`, and `format`.

Lovable's summary claimed "typecheck and lint clean". It did not claim tests, and none exist, so
**no unit-test or accessibility-check result can be reported for this foundation**. Typecheck and
lint must be re-run independently from the clone (`tsc --noEmit` and `eslint .`) rather than taken
on trust.

**Correction:** add Vitest, React Testing Library, Playwright, and axe, plus a `typecheck` script,
in Phase 1.

**Finding G — the package name is the template default.** `package.json` still declares
`"name": "tanstack_start_ts"`. Rename to `sentinelflow-web`.

## Routes generated

All eleven specified routes are present:
`/login` · `/` · `/transactions/live` · `/transactions/$transactionId` · `/alerts` ·
`/alerts/$alertId` · `/reports` · `/model` · `/health` · `/about` · `/forbidden`, plus a root
`notFoundComponent` (404) and an `errorComponent` with a working retry that calls
`router.invalidate()`.

Supporting primitives were generated as reusable components rather than duplicated per screen:
`app-shell`, `chart-frame`, `chips`, `data-state` (loading/empty/error), `panel`, and
`pagination-bar`. Domain vocabulary is centralised in `src/domain/types.ts` and
`src/domain/labels.ts`.

Rendering, keyboard focus, semantic risk colours, responsive behaviour, and accessibility
conformance are **not yet verified** — they require the clone plus a browser and axe run, and are
tracked as the remaining Phase 0 verification item.

## Corrections applied

Each landed as its own reviewable commit on `chore/phase-0-foundation`. Lovable's generated work
was preserved: nothing was removed except the out-of-scope authentication shape and one dead
dependency.

| Commit    | Change                                                                     | Findings closed                                |
| --------- | -------------------------------------------------------------------------- | ---------------------------------------------- |
| `c57dcee` | `chore(repo): normalize line endings via .gitattributes`                   | prerequisite for reproducible lint (see below) |
| `bfaf2d0` | `fix(web): remove persisted demo session and route gate`                   | A, B                                           |
| `f19cd99` | `refactor(web): render the console client-side`                            | C                                              |
| `45ee061` | `chore(web): drop unused TanStack Query dependency`                        | D                                              |
| `83c9610` | `chore(web): name the package and add a reproducible script surface`       | G, and part of F                               |
| `a59001a` | `test(web): add Vitest, Testing Library, and axe with a scope-guard suite` | F                                              |
| `2f8ab74` | `test(e2e): add Playwright browser, accessibility, and responsive checks`  | F                                              |
| `ee8bc40` | `ci(web): add quality, browser, and security workflows`                    | enforcement                                    |

### What replaced the authentication shape

`sessionSlice` became `demoOperatorSlice`, holding exactly `{ operatorId, role }`.
`signedIn`, `hydrated`, `hydrateSession`, `mockSignIn`, and `signOut` are gone, and
`session-bootstrap.tsx` was deleted outright. The sign-in screen survives as a presentational
demonstration with a strengthened disclaimer; "Sign out" became "Change demo operator".

Two automated guards now fail the build if the boundary is crossed again:

- `tests/unit/demo-operator.test.ts` asserts the state keys are exactly `operatorId` and `role`,
  and that `signedIn`, `hydrated`, `token`, `accessToken`, and `password` are absent.
- `tests/unit/no-browser-storage.test.ts` asserts no source file calls
  `localStorage`/`sessionStorage` `get/set/removeItem`, and that dispatching through a real store
  leaves both storages empty.
- `tests/e2e/console.spec.ts` asserts every route is reachable by deep link (no gate), that no
  `input[type="password"]` exists, and that the "no authentication, no credential check" text is
  visible.

### Deferred, with reasons

- **Finding E — Google Fonts is loaded from `fonts.googleapis.com`.** A deliberate font request,
  not an API call, so it does not breach "no unintended network requests". Self-hosting is a
  Phase 6 item so the local-first stack has no external runtime dependency.
- **`noUnusedLocals` / `noUnusedParameters` are `false`.** Worth enabling in Phase 1 so dead code
  fails the build rather than accumulating.
- **Risk-band distribution chart uses one colour for all four bands.** The axis labels carry the
  meaning, so this is not an accessibility defect, but severity-coded bars would read better.
  Phase 6.

## Evidence

All commands run from the repository root on 2026-08-25.

| Command                 | Result                                                                 |
| ----------------------- | ---------------------------------------------------------------------- |
| `bunx tsc --noEmit`     | **exit 0** — Lovable's "typecheck clean" claim independently confirmed |
| `bunx eslint .`         | **exit 0** — 0 errors, 7 warnings                                      |
| `bun run test`          | **24 passed / 24**, 5 files                                            |
| `bun run test:coverage` | 40.4% lines, 40.11% statements, 29.64% branches                        |
| `bun run test:e2e`      | **58 passed / 58** (29 desktop + 29 tablet)                            |
| `bun run build`         | **exit 0** — prerendered 1 page                                        |

### The lint claim needed a caveat

`eslint .` initially reported **8927 errors**. Every one was `prettier/prettier` `Delete ␍`.
The committed blobs are LF-only (`git cat-file -p HEAD:vite.config.ts | tr -cd '
' | wc -c`
returns `0`); the working tree had CRLF because the machine's global `core.autocrlf` is `true` and
the repository shipped no `.gitattributes`. So Lovable's "lint clean" claim was true on its Linux
sandbox and false on any default Windows checkout. Committing `.gitattributes` with
`* text=auto eol=lf` fixed it: 0 errors.

The 7 remaining warnings are all `react-refresh/only-export-components` in vendored shadcn/ui
files that export both a component and its `cva` variants. They are upstream boilerplate and
affect dev-server fast refresh only.

### Accessibility

axe-core reports **zero WCAG 2.1 A/AA violations** on all eight routes, at both 1440×900 and
768×1024. Verified in Chromium against the production build — not jsdom, which has no canvas and
therefore cannot evaluate colour contrast at all.

Also verified in the browser: the skip link is the first tab stop and moves focus to the main
landmark; primary navigation is keyboard-operable; focused controls carry a visible outline or
ring; and the page does not scroll horizontally at tablet width.

Manual visual review confirmed the design intent holds: a restrained dark navy/slate palette,
dense readable tables, monospace identifiers, and risk/status chips that pair an icon **and** a
text label with colour, so status never depends on colour alone.

### What is still unverified

- **Screen-reader behaviour.** axe cannot substitute for a manual pass with NVDA or VoiceOver.
  Scheduled for Phase 6 and to be recorded in `docs/frontend/ACCESSIBILITY.md`.
- **Route-level unit coverage.** At 40.4% lines, route components are exercised by the browser
  tests rather than by Vitest. Coverage thresholds are set in Phase 1 once a baseline exists, per
  the project rule against writing meaningless tests to inflate a number.
