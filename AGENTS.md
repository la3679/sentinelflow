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

## Ownership boundary

The backend, security, authentication, authorization, risk rules and ML scoring
are **owned outside Lovable**, in a separate Spring Boot / Kafka / PostgreSQL /
FastAPI monorepo. Do not implement authentication, authorization enforcement,
risk rules or model logic in this repository.

Role handling in this console (`ANALYST`, `ADMINISTRATOR`, `AUDITOR`) is a
user-experience affordance only — never treat it as a security boundary.

## Mock data layer

`src/mocks/` is a **temporary** deterministic fixture layer. It exists so the
console runs with no backend and will be deleted once the real client is wired
up. RTK Query endpoints in `src/api/sentinelApi.ts` already declare real
`/api/v1` request descriptors, so the migration is limited to replacing
`mockBaseQuery` with `fetchBaseQuery({ baseUrl: API_BASE_URL })`.

`API_BASE_URL` comes from `VITE_API_BASE_URL` and defaults to `/api/v1`.

## Conventions

- State and data access: Redux Toolkit + RTK Query. Do not introduce another
  data-fetching library.
- TypeScript strict; no casual `any`; no `console.log` in committed code.
- Money is always a decimal string plus an explicit currency code. Never do
  floating-point arithmetic on amounts.
- Synthetic identifiers only (`ACC-000123`, `MER-0042`, `TXN-000517`). No
  realistic names, addresses, card numbers or personal identifiers.
- One accessible dark theme. All colour, spacing and radius values come from the
  tokens in `src/styles.css`; no hardcoded colour utilities in components.
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
