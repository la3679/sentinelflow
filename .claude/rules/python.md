# Python rules — `apps/scoring`

Binding for every change under `apps/scoring`. Python 3.13 exactly, managed by
`uv` (ADR-0004). Never `pip install` into the environment; change
`pyproject.toml` and run `uv sync`, so `uv.lock` stays the truth.

## Typing

`mypy --strict` passes, and it is a gate rather than a report. Every function
has annotated parameters and an annotated return. `Any` needs a comment
explaining why no narrower type is possible.

A service that returns risk scores should not be guessing at its own types.

## Structure

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
ADR-0010 section 6's — training is a command, never an API side effect — and it is
visible as a one-way dependency: serving imports the registry and the score
rescale, and nothing in training imports serving. `models/` is taken by the
registry directory on disk, which is data rather than code.

- **The application is built by a factory that takes settings**, not by reading
  a module-level singleton. That is what makes it constructible under test
  without mutating the environment.
- **A route validates, delegates, and returns.** Feature engineering and
  inference live behind it.

## Configuration

pydantic-settings with `extra="forbid"`. A typo in a `SENTINELFLOW_SCORING_*`
variable stops startup instead of silently leaving a default in place, and there
is a test that proves it. No secret carries a default.

## Money and identifiers

Money is a decimal string with an explicit currency code across the API
boundary. Use `decimal.Decimal` internally — never `float`. Identifiers are
synthetic: `ACC-000123`, `MER-0042`, `TXN-000517`.

## Feature engineering and models

- **Features are versioned and deterministic.** The same input and the same
  feature version produce the same vector, on any machine, on any day.
- **No future information.** A feature computed for a transaction may only use
  data that existed before it. Leakage produces a model that scores beautifully
  and is worthless.
- **Split by entity, not by row.** The same account must not appear in both
  train and test.
- **Never publish a model on accuracy alone.** The classes are extremely
  imbalanced, so accuracy is close to meaningless. Report precision, recall,
  PR-AUC, and the operating threshold, and record the run that produced them.
- Every response carries `model_version`, `feature_version`, and the reason
  codes that explain the score.

## Errors

- Raise a specific exception. Never a bare `except:`, never `except Exception`
  without re-raising or handling deliberately.
- An error response never contains a traceback, a file path, or an input echo
  that could carry data back to a caller who should not see it.

## Tests

- pytest, with `filterwarnings = ["error"]`. A deprecation warning is a
  failure, not scrollback — it is what caught Starlette's move to httpx2.
- Exercise the application through `TestClient` rather than calling handler
  functions, so what is asserted is what a caller receives.
- Model tests assert determinism and the absence of leakage, not just that the
  code runs.

## Style

`ruff check` and `ruff format --check` are gates. The bandit (`S`) rules are on;
a suppression needs an inline reason, not a bare `noqa`.
