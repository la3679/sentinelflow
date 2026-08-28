<!-- LOVABLE:BEGIN -->

> [!IMPORTANT]
> This project is connected to [Lovable](https://lovable.dev). Avoid rewriting
> published git history — force pushing, or rebasing/amending/squashing commits
> that are already pushed — as it rewrites history on Lovable's side and the
> user will likely lose their project history.
>
> Commits you push to the connected branch sync back to Lovable and show up in
> the editor, so keep the branch in a working state.

<!-- LOVABLE:END -->

# SentinelFlow — agent notes

SentinelFlow is an independent, educational, open-source portfolio project: a
transaction-risk and fraud-operations console for reviewing **synthetic**
financial transactions. It is not affiliated with any bank, financial
institution or employer, executes no real transactions, and makes no real
financial decisions.

## Repository layout

This is a polyglot monorepo. The console is one application inside it:

| Path            | Application                                            |
| --------------- | ------------------------------------------------------ |
| `apps/web/`     | this React console — the only part Lovable should edit |
| `apps/api/`     | Spring Boot transaction, alert and outbox service      |
| `apps/scoring/` | FastAPI risk-scoring and model service                 |

## Ownership boundary

The backend, security, authentication, authorization, risk rules and ML scoring
are **owned outside Lovable**, in `apps/api/` and `apps/scoring/`. Do not
implement authentication, authorization enforcement, risk rules or model logic
in `apps/web/`.

Role handling in this console (`ANALYST`, `ADMINISTRATOR`, `AUDITOR`) is a
user-experience affordance only — never treat it as a security boundary.

## Where the console's data comes from

**There is no mock layer.** `apps/web/src/mocks/` is deleted and every screen
reads the API through `apps/web/src/api/transport.ts`, which attaches the
operator's bearer token and maps every RFC 9457 problem document to one error
shape. An earlier version of this file said the migration was "limited to
replacing `mockBaseQuery` with `fetchBaseQuery`";
[`docs/frontend/API_MIGRATION_AUDIT.md`](docs/frontend/API_MIGRATION_AUDIT.md)
checked it endpoint by endpoint, found four pieces of work rather than one, and
is now closed.

**Do not reintroduce a fixture layer to make a screen look finished.** Where the
API cannot answer something — throughput per hour, latency percentiles, consumer
lag — the screen says so and names the phase that measures it. Three panels of
invented numbers were deleted for that reason, and
[ADR-0014](docs/adr/0014-where-the-console-s-remaining-screens-get-their-data.md)
records where each remaining screen's data comes from.

The end-to-end suite stubs the API at the network boundary in
`tests/e2e/fixtures.ts`, in the contract's own shapes. That is the one place
synthetic responses belong: it exercises the real transport, the real bearer
header and the real 401 with no backend anywhere.

`API_BASE_URL` comes from `VITE_API_BASE_URL` and defaults to
`http://localhost:8080/api/v1`. It is **absolute**, because the console and the
API are separate origins ([ADR-0013](docs/adr/0013-console-to-api-cross-origin-access.md));
whatever it points at must list the console's origin in
`SENTINELFLOW_CORS_ALLOWED_ORIGINS`. Vite inlines it at build time, so changing
it means rebuilding.

## Conventions

- State and data access: Redux Toolkit + RTK Query. Do not introduce another
  data-fetching library.
- TypeScript strict; no casual `any`; no `console.log` in committed code.
- Money is always a decimal string plus an explicit currency code. Never do
  floating-point arithmetic on amounts.
- Synthetic identifiers only (`ACC-000123`, `MER-0042`, `TXN-000517`). No
  realistic names, addresses, card numbers or personal identifiers.
- One accessible dark theme. All colour, spacing and radius values come from the
  tokens in `apps/web/src/styles.css`; no hardcoded colour utilities in components.
- Amber/orange/red are reserved for genuine risk or operational severity. Do not
  use green as decoration where it could imply "safe" or "approved".
- Accessibility target is WCAG 2.2 AA: landmarks, heading order, keyboard
  operation, visible focus, status conveyed by icon + text, table semantics,
  accessible validation errors, `prefers-reduced-motion` respected.
- Every data view needs loading, empty, error-with-retry and bounded/paginated
  states. No dead controls.
- Never state performance, accuracy or false-positive-reduction claims in UI copy.

## Review

All Lovable output in this repository is reviewed and tested by a human before
merge.
