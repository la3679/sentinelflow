# Engineering standards

The rules a change to this repository is expected to follow, per application.
[`CONTRIBUTING.md`](../../CONTRIBUTING.md) covers the workflow — branches, commits, tests and
review. This document covers the code.

These are not style preferences. Each one exists because breaking it caused a real problem here, or
because the alternative is a defect class this project has decided not to have.

---

## Java and Spring Boot — `apps/api`

Java 25 LTS, Spring Boot 4.1.1 ([ADR-0003](../adr/0003-java-and-spring-boot-versions.md)). Versions
come from the Spring Boot BOM; do not pin one it manages.

### Layering

```text
web/          controllers, request and response DTOs, exception handling
service/      business rules, transactions, orchestration
domain/       entities, value objects, domain events, repository interfaces
persistence/  JPA entities, Spring Data repositories, mappers
messaging/    Kafka producers, consumers, outbox relay
config/       configuration properties and beans
```

- **A controller validates, delegates, and maps.** Never a business rule, never a repository call,
  never a Kafka publish.
- **A JPA entity never crosses the web boundary.** Map to a DTO. Exposing an entity leaks the schema
  into the API contract and makes a lazy association a serialization bug.
- **A service owns the transaction.** `@Transactional` goes on the service method, not on the
  controller and not on the repository.

### Money

`BigDecimal`, always, with an explicit currency code. Never `double`, never `float`, never a
floating-point intermediate in a calculation, and never `BigDecimal.equals` for a value comparison —
use `compareTo`.

The database column is `NUMERIC`. The API and event representations are decimal strings, so a
JavaScript consumer cannot silently round them.

### Errors

- Throw a domain-meaningful exception. Do not throw `RuntimeException`.
- Handle it once, at the boundary, in a `@RestControllerAdvice`.
- Never catch and ignore. Never log-and-rethrow the same exception at every layer — one log, at the
  place that decides what to do about it.
- The response body is RFC 9457 `application/problem+json` and never contains a stack trace, a SQL
  fragment, or an internal class name.

### Validation and boundaries

- Bean Validation on every inbound DTO. Validate at the boundary, not in a service that assumes the
  boundary already did it.
- Every list endpoint is paged, with a maximum page size the server enforces. **An endpoint whose
  result grows with the dataset is a denial-of-service primitive.**
- Idempotency keys on every mutating ingestion endpoint.

### Persistence

- Flyway owns the schema. `ddl-auto` is `validate`, never `update`.
- A migration is immutable once merged. Fix a mistake with a new migration.
- Every migration is tested against real PostgreSQL through Testcontainers. **H2 is not evidence
  that a PostgreSQL migration works.**

### Messaging

- The outbox is the only way a state change becomes an event. Never publish to Kafka inside the same
  code path that writes the row without the outbox — the two are not atomic.
- Consumers are idempotent. At-least-once delivery means a duplicate is normal, not exceptional.
- Every event carries the envelope defined in [`contracts/asyncapi/`](../../contracts/asyncapi/).

### Tests and style

JUnit 5 and AssertJ. `@SpringBootTest` when the wiring is the thing under test; a plain unit test
when it is not. Testcontainers for PostgreSQL and Kafka — a suite that mocks both away is not an
integration test. Assert behaviour, not implementation: a test that only verifies a mock was called
proves the code calls itself.

Spotless with palantir-java-format runs at `verify` and fails the build. Run `./mvnw spotless:apply`
rather than arguing with it.

---

## Python — `apps/scoring`

Python 3.13 exactly, managed by `uv` ([ADR-0004](../adr/0004-python-runtime-and-model-stack.md)).
Never `pip install` into the environment; change `pyproject.toml` and run `uv sync`, so `uv.lock`
stays the truth.

### Typing

`mypy --strict` passes, and it is a gate rather than a report. Every function has annotated
parameters and an annotated return. `Any` needs a comment explaining why no narrower type is
possible. A service that returns risk scores should not be guessing at its own types.

### Structure

```text
src/sentinelflow_scoring/
  app.py        FastAPI application factory, routes, error handling, middleware
  config.py     pydantic-settings, validated at startup
  features/     feature engineering and request schemas - deterministic, versioned
  serving/      model loading, inference, reason codes, response schemas, collectors
  training/     the offline command: comparison, evaluation, the model registry
tests/          mirrors src/
models/         committed registry entries: <model-name>/<model-version>/
```

**`serving/` and `training/` rather than one `models/` package.** The split is
[ADR-0010](../adr/0010-model-selection-and-evaluation.md) §6's — training is a command, never an API
side effect — and it is visible as a one-way dependency: serving imports the registry and the score
rescale, and nothing in training imports serving.

- **The application is built by a factory that takes settings**, not by reading a module-level
  singleton. That is what makes it constructible under test without mutating the environment.
- **A route validates, delegates, and returns.** Feature engineering and inference live behind it.

### Configuration

pydantic-settings with `extra="forbid"`. A typo in a `SENTINELFLOW_SCORING_*` variable stops startup
instead of silently leaving a default in place, and there is a test that proves it. No secret carries
a default.

### Feature engineering and models

- **Features are versioned and deterministic.** The same input and the same feature version produce
  the same vector, on any machine, on any day.
- **No future information.** A feature computed for a transaction may only use data that existed
  before it. Leakage produces a model that scores beautifully and is worthless.
- **Split by entity, not by row.** The same account must not appear in both train and test.
- **Never publish a model on accuracy alone.** The classes are extremely imbalanced, so accuracy is
  close to meaningless. Report precision, recall, PR-AUC, and the operating threshold, and record
  the run that produced them.
- Every response carries `model_version`, `feature_version`, and the reason codes that explain the
  score.

### Errors, tests and style

Raise a specific exception; never a bare `except:`, and never `except Exception` without re-raising
or handling deliberately. An error response never contains a traceback, a file path, or an input
echo that could carry data back to a caller who should not see it.

pytest with `filterwarnings = ["error"]` — a deprecation warning is a failure, not scrollback.
Exercise the application through `TestClient` rather than calling handler functions, so what is
asserted is what a caller receives. Model tests assert determinism and the absence of leakage, not
just that the code runs.

`ruff check` and `ruff format --check` are gates. The bandit (`S`) rules are on; a suppression needs
an inline reason, not a bare `noqa`.

---

## Frontend — `apps/web`

TanStack Start, React 19, TypeScript, Tailwind 4 and shadcn/ui, Redux Toolkit with RTK Query,
Recharts, React Hook Form with Zod
([ADR-0009](../adr/0009-frontend-component-library.md)). Bun is the only package manager.

The console renders **client-side**. Do not reintroduce the Nitro SSR server: Spring Boot is the
only application backend, and a second server runtime is another deployment artifact and another
thing the threat model has to cover.

### TypeScript

`strict` is on, along with `noUncheckedIndexedAccess` and `exactOptionalPropertyTypes`. No casual
`any` — an unavoidable one carries a comment saying why. `as` casts are a smell; narrow with a type
guard instead.

### State and data

- **RTK Query owns server state.** Do not add a second data-fetching library.
- **Redux owns client state that outlives a component.** Component state owns everything else. Not
  every value belongs in a store.
- **There is no mock layer, and it does not come back.** Every screen reads the API through
  `src/api/transport.ts`. Where the API cannot answer something, the screen says so — a fixture
  added to make a screen look finished is the thing this rule exists to stop.
- **A screen stays current by polling, not by streaming**
  ([ADR-0015](../adr/0015-live-updates-polling-and-server-sent-events.md)). The cache re-reads on
  focus and on reconnect; two screens add an interval, each a named constant beside its query,
  applied through `refreshWhile` so that a feed nobody is watching does not poll. A third interval is
  a reason to reopen ADR-0015 rather than to add one.
- **No browser storage for session or authorization state.** There is a test that enforces this.

### Components

- Small and focused. A component that renders, fetches, transforms, and decides is four things.
- **No business logic in a component.** Risk rules, thresholds, and state transitions belong to the
  API.
- **Role handling (`ANALYST`, `ADMINISTRATOR`, `AUDITOR`) is a user-experience affordance. It is
  never a security boundary.** Disabling a control does not authorize anything; the server does that.

### Design tokens

Every colour, spacing and radius value comes from the tokens in `src/styles.css`. No hardcoded
colour utilities in components.

Amber, orange and red are reserved for genuine risk or operational severity. Green is never
decoration — in a fraud console it reads as "safe" or "approved", which is a claim the UI must not
make accidentally. One accessible dark theme, done well, rather than a half-finished pair.

### Accessibility — WCAG 2.2 AA

Not optional, and not satisfied by an automated pass alone:

- Semantic landmarks and correct heading order
- Full keyboard operation, with a visible focus indicator on every control
- Status conveyed by **icon and text**, never colour alone
- Proper table semantics, and dialog focus management
- Accessible validation errors, associated with their field
- `prefers-reduced-motion` respected
- Adequate target sizes

axe runs in Playwright across every route at two viewports. **axe finds roughly a third of real
issues** — it is a floor, not a ceiling, and it is not evidence that a screen reader works.

### Every data view

Loading, empty, error-with-retry, and paged or bounded. All four, every time.

**No dead controls.** A visible control either works against the current data layer or is visibly
marked as a documented future feature.

### Copy

Never state a performance, accuracy, or false-positive-reduction figure in the UI. Every screen makes
it discoverable that the data is synthetic and the project is independent.

### Tests

Vitest and React Testing Library for units — query by role and accessible name, not by test id, so
the test fails when the accessible name breaks. Playwright for behaviour that needs a real browser:
contrast, focus visibility, layout, and deep links.
[`make verify-real-stack`](../testing/TEST_RESULTS.md) drives the console against the running Compose
stack with nothing stubbed. Run `bun run verify` before pushing.

---

## Applies everywhere

- **Contracts are authoritative.** [`contracts/`](../../contracts/) is the source of truth. Changing
  a contract means updating producers, consumers, tests and documentation in the same change.
- **ADRs are binding** until superseded by a new ADR. Do not quietly re-decide.
- **Tests and documentation ship with behaviour.** A feature is not done without them.
- **No secrets, ever** — no tokens, keys, `.env` files, passwords or real financial data in the
  repository, logs, metrics labels, event payloads or commit messages.
- **No invented numbers.** Coverage, latency, throughput, false-positive rates and test counts are
  only ever reported from an actual run, with the command and the date recorded.
- **Synthetic data only.** No real or realistic personal data — no real names, addresses, national
  identifiers or card numbers.
