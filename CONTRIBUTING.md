# Contributing to SentinelFlow

Thank you for looking. SentinelFlow is an independent, educational, open-source
project built on **synthetic data only**. Before contributing, please read the
[Code of Conduct](CODE_OF_CONDUCT.md) and the [security policy](SECURITY.md).

## Before you start

**Security problems do not go here.** Report them privately through a
[GitHub security advisory](https://github.com/la3679/sentinelflow/security/advisories/new).

**Open an issue before a large change.** A proposal that adds a technology
without a demonstrated need — another datastore, an orchestrator, a graph
database, an LLM — is unlikely to be accepted. The non-goals are in
[`docs/planning/PRODUCT_REQUIREMENTS.md`](docs/planning/PRODUCT_REQUIREMENTS.md).

**Never contribute real data.** No real names, addresses, national identifiers,
card numbers, account numbers, or any other personal or financial data —
including in tests, fixtures, screenshots, and issue reports. Synthetic
identifiers look like `ACC-000123`, `MER-0042`, `TXN-000517`.

## Getting set up

You need Docker, [Bun](https://bun.sh), [uv](https://docs.astral.sh/uv) and Git.
A JDK 25 is optional — only needed to build `apps/api` outside a container.

```bash
git clone https://github.com/la3679/sentinelflow.git
cd sentinelflow
make bootstrap     # checks prerequisites, generates a local .env
make up            # starts the stack and waits for every service to be healthy
make smoke         # 23 checks against the running stack
```

On Windows without `make`, every target is available through PowerShell:

```powershell
.\scripts\dev\sf.ps1 bootstrap
.\scripts\dev\sf.ps1 up
.\scripts\dev\sf.ps1 smoke
```

Run `make help` (or `sf.ps1 help`) for the full list.

## Repository layout

| Path            | What lives there                                              |
| --------------- | ------------------------------------------------------------- |
| `apps/api/`     | Spring Boot: ingestion, outbox, alert lifecycle, audit        |
| `apps/scoring/` | FastAPI: feature engineering, model inference, model registry |
| `apps/web/`     | React console                                                 |
| `contracts/`    | OpenAPI, AsyncAPI, and event schemas — authoritative          |
| `docs/adr/`     | Architecture decisions — binding until superseded             |
| `infra/`        | Prometheus, Grafana, and container configuration              |
| `scripts/`      | Developer, smoke, and maintenance scripts                     |

See [ADR-0002](docs/adr/0002-monorepo-and-service-boundaries.md) for why the
boundaries are where they are.

## Making a change

1. **Branch** from `main` with a descriptive name: `feat/kafka-outbox`,
   `fix/csv-formula-injection`, `docs/architecture-diagrams`.
2. **Work in small, coherent commits.** One understandable change per commit,
   with its tests. Do not batch unrelated changes.
3. **Run the gates before pushing:**

   ```bash
   make format-check
   make lint
   make test
   ```

4. **Open a pull request** using the template. Fill in the test-evidence table
   with commands you actually ran and results you actually saw.

### Commit messages

[Conventional Commits](https://www.conventionalcommits.org/), with a scope:

```text
feat(api): persist transactions with idempotency keys
fix(security): prevent spreadsheet formula injection in CSV export
test(db): validate Flyway migrations on PostgreSQL
docs(adr): define event and API compatibility policy
```

Explain _why_ in the body when the change is not self-evident. Do not add
AI-generation boilerplate to every message.

## Standards this repository enforces

These are not style preferences; a pull request that breaks one will be asked to
change. The per-application rules — Java layering, Python typing and model
discipline, console state and accessibility — are in
[`docs/development/ENGINEERING_STANDARDS.md`](docs/development/ENGINEERING_STANDARDS.md).
What follows applies everywhere.

- **Money is never a floating-point number.** Decimal, with an explicit currency
  code, everywhere — database, API, events, and UI.
- **No unbounded endpoints.** Every list, export, and replay endpoint is paged
  or explicitly bounded.
- **Persistence entities never appear on an API contract.** Map to a DTO.
- **No business logic in a controller or a React component.**
- **No swallowed exceptions**, and no log-and-rethrow at every layer.
- **No broad `any`** in TypeScript. `mypy --strict` passes in `apps/scoring`.
- **No invented numbers.** Coverage, latency, throughput, accuracy, and test
  counts are only ever quoted from a run, with the command recorded.
- **No dead controls.** A visible control either works or is visibly marked as
  a documented future feature.
- **Accessibility is a requirement, not a nice-to-have.** WCAG 2.2 AA:
  landmarks, heading order, keyboard operation, visible focus, status conveyed
  by more than colour, and accessible validation errors.
- **Contracts and ADRs are binding.** Changing a contract means updating its
  producers, consumers, tests, and documentation in the same change. Disagreeing
  with an ADR means writing one that supersedes it, not quietly deciding
  otherwise.

## Tests

A change to behaviour ships with tests for that behaviour.

| Suite            | Command             | What it covers                     |
| ---------------- | ------------------- | ---------------------------------- |
| Console unit     | `make test-web`     | Components, state, API client      |
| API              | `make test-api`     | Spring Boot, including Spotless    |
| Scoring          | `make test-scoring` | pytest, with mypy in `make lint`   |
| Browser and a11y | `make test-e2e`     | Playwright plus axe, two viewports |
| Running stack    | `make smoke`        | Every service over the network     |

PostgreSQL and Kafka are tested against real instances through Testcontainers.
H2 is not acceptable as evidence that a PostgreSQL migration works, and a test
suite that mocks away both the database and the broker is not an integration
test.

`make verify-real-stack` goes one step further and drives the console against
the running Compose stack with nothing stubbed. It needs `make up` first, and it
is the check that catches what a green build cannot see.

## Documentation

Documentation drifting from the code is treated as a defect. If you change a
public contract, a workflow, a command, or a configuration variable, update the
document that describes it in the same pull request.

## Licence

Contributions are licensed under [Apache-2.0](LICENSE), matching the project.
Do not copy GPL-licensed source into this repository — it is incompatible.
