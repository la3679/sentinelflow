# SentinelFlow

Create a project named "SentinelFlow". This first change is ONLY the reviewed frontend foundation and design system — not the complete application. A separate Spring Boot + Kafka + PostgreSQL + Python backend is being built outside Lovable and will replace the mock data later.

## Product
SentinelFlow is an independent, educational, open-source portfolio project: a transaction-risk and fraud-operations console for reviewing SYNTHETIC financial transactions. It is not affiliated with any bank, financial institution, or employer; it executes no real transactions, makes no real financial decisions, and uses only fictional synthetic data.

## Stack requirements (important)
- Vite + React + TypeScript in STRICT mode (not Next.js, not TanStack Start — plain Vite SPA)
- React Router for routing
- Redux Toolkit + RTK Query for state and data access (please use RTK Query, not TanStack Query). RTK Query endpoints should point at a typed `/api/v1` base URL read from an env var, but for now be backed by deterministic in-memory mock fixtures behind a clearly named mock layer so the app runs with no backend.
- Tailwind + shadcn/ui for components
- Recharts for charts
- React Hook Form + Zod for forms
- No Supabase, no database, no backend, no authentication implementation, no API secrets, no cloud integrations in this change.

## Visual direction
A credible, restrained, information-dense financial-operations console for extended analyst work. NOT a marketing landing page and NOT a neon "AI dashboard".
- Deep navy/slate neutral dark foundation, high contrast, compact professional typography
- Restrained semantic colors: red/amber reserved strictly for genuine risk or operational severity; do NOT use green as decoration where it could imply "safe" or "approved"
- Consistent 8-point spacing rhythm
- Clearly visible keyboard focus states
- Dense but readable data tables
- Desktop-first responsive layout that still works on tablet
- No glassmorphism, no gradients, no stock photos of banks or hackers, no logos or trademarks, no excessive animation
- Respect `prefers-reduced-motion`
- Ship ONE excellent accessible dark theme rather than a half-finished light/dark pair

## Accessibility — target WCAG 2.2 AA
Semantic landmarks and heading order, full keyboard operation, visible focus, status communicated by more than color alone (icon + text label with every risk/status chip), screen-reader labels, proper table semantics, dialog focus management, accessible validation errors, adequate target sizes, tested contrast.

## Screens and routes
1. `/login` — Sign-in: product identity, visible demo/synthetic disclaimer, accessible form with inline validation errors. Mock only — no real auth, no hardcoded token.
2. `/` — Operations overview: transaction throughput chart, risk-band distribution, open alert counts by status, scoring latency summary, pipeline/DLQ health indicators, recent alerts table.
3. `/transactions/live` — Live transactions: simulated streaming feed with pause/resume, filtering and search, risk-band and status chips.
4. `/alerts` — Alert queue: paginated, filterable table with priority, status, final score, age, top reason code, assignee.
5. `/alerts/:alertId` — Alert detail / investigation: transaction summary, risk breakdown (rule score, model score, final score), reason codes with contributions, investigation timeline, assignment control, notes, valid state-transition actions, audit history.
6. `/transactions/:transactionId` — Transaction detail: transaction fields, related account activity, assessment and model metadata, correlation/trace reference.
7. `/reports` — Reports: trend charts, risk-band distribution, analyst feedback summary, a CSV export control.
8. `/model` — Model and policy: READ-ONLY active model version, feature version, metrics summary, threshold/policy metadata, and an explicit limitations panel.
9. `/health` — System health: status cards for API, scoring service, Kafka, and database, plus consumer lag and DLQ indicators.
10. `/about` — About: full independent-project and synthetic-data disclaimer.
11. Dedicated 404 Not Found, 403 Forbidden, and recoverable error screens.

## Domain vocabulary to model in the mock fixtures and TypeScript types
- Risk bands: `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`
- Alert statuses: `NEW`, `IN_REVIEW`, `ESCALATED`, `CONFIRMED_SUSPICIOUS`, `DISMISSED_FALSE_POSITIVE`, `CLOSED`
- Roles: `ANALYST`, `ADMINISTRATOR`, `AUDITOR` (auditor is read-only — render mutating controls as disabled with an accessible explanation; treat this as UX only, never as security)
- A risk assessment carries: ruleScore, modelScore, finalScore, riskBand, modelVersion, featureVersion, policyVersion, reasonCodes[], scoringLatencyMs
- Use fictional identifiers only (e.g. account `ACC-000123`, merchant `MER-0042`). No realistic names, addresses, card numbers, or personal identifiers. Money as decimal strings with an explicit currency code — never floating-point arithmetic on amounts.

## UX completeness
Every data view must have a loading/skeleton state, an empty state, an error state with retry, and pagination or bounded virtualization. No dead controls: if a control is visible it must work against the mock layer, or be clearly and visibly marked as a documented future feature.

## Code quality
Small focused components, reusable design tokens, no casual `any`, no `console.log` in committed code, no invented performance, accuracy, or false-positive-reduction claims anywhere in the UI copy.

## Also include
A concise root `AGENTS.md` stating that: SentinelFlow's backend, security, authorization, risk rules, and ML scoring are owned outside Lovable in a Spring Boot / Kafka / PostgreSQL / FastAPI monorepo; the mock fixture layer is temporary and will be replaced by the real `/api/v1` client; and all Lovable output is reviewed and tested before merge.

Keep the change coherent, componentized, and buildable.

This project was built with [Lovable](https://lovable.dev).

## Build with Lovable

Continue developing this project in the [Lovable editor](https://lovable.dev/projects/e1341a35-a595-4af4-b0a5-c158ba286897).

- **Ship faster**: describe what you want to build and Lovable handles the code.
- **Stay in sync**: every change made in Lovable is committed straight to this repository.
- **Full ownership**: this code is yours. Push to `main` on GitHub and your changes sync back into Lovable, ready for your next prompt.

## Development

Prefer working locally? You need Node.js and npm — [install with nvm](https://github.com/nvm-sh/nvm#installing-and-updating).

```sh
git clone <this-repository-url>
cd <repository-name>
npm i
npm run dev
```
