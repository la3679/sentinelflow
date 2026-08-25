# ADR-0004 — Python 3.13 with uv for the scoring service

- **Status:** Accepted
- **Date:** 2026-08-25
- **Research:** R-2026-08-25-06 in [`docs/research/RESEARCH_LOG.md`](../research/RESEARCH_LOG.md)

## Context

`apps/scoring` is a FastAPI service that serves a scikit-learn model and must have a fully
reproducible environment. The runtime has to satisfy every package in the scientific stack
simultaneously — FastAPI, Pydantic v2, scikit-learn, NumPy, pandas, joblib — plus the test, lint,
type-check, and instrumentation tooling.

Checking `requires_python` and the published classifiers for each selected package produced two
binding constraints:

- **NumPy 2.5.2 requires Python >= 3.12** — the floor.
- **joblib 1.5.3 declares support only through Python 3.13** — the ceiling.

The intersection where _every_ selected package declares support is therefore exactly **3.13**.

## Decision

**Target Python 3.13**, managed by **`uv`** with a committed `uv.lock`.

Python 3.14 is deliberately not selected. joblib sits on the model-serialization path, and model
serialization is not a place to run on a runtime the library does not yet claim to support.

Selected versions, all verified against PyPI on 2026-08-25:

| Package           | Version | Package           | Version |
| ----------------- | ------- | ----------------- | ------- |
| fastapi           | 0.141.1 | pytest            | 9.1.1   |
| uvicorn           | 0.52.4  | pytest-cov        | 7.1.0   |
| pydantic          | 2.13.4  | ruff              | 0.16.4  |
| pydantic-settings | 2.15.0  | mypy              | 2.3.1   |
| scikit-learn      | 1.9.0   | prometheus-client | 0.26.0  |
| numpy             | 2.5.2   | opentelemetry-sdk | 1.44.0  |
| pandas            | 3.0.5   | structlog         | 26.1.0  |
| joblib            | 1.5.3   |                   |         |

## Consequences

**Positive** — one pinned, lockfile-reproducible interpreter across local development, CI, and the
container image. `uv` provisions 3.13 itself, so the reference machine's system Python 3.11.9
(which is below NumPy's floor) needs no change and is not touched.

**Negative** — contributors must have `uv`; `pip install -r requirements.txt` is not a supported
path. Documented in `docs/development/LOCAL_DEVELOPMENT.md`.

**Security** — model artifacts are loaded only from the project's own controlled training
pipeline, with checksum and metadata verification before activation. An untrusted joblib or pickle
artifact is never loaded; this is covered in `docs/security/THREAT_MODEL.md`.

**Follow-up** — revisit Python 3.14 once joblib declares support. Tracked as an open item in the
research log.
