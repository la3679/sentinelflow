# SentinelFlow scoring service

Risk scoring over synthetic transactions, exposed as a typed FastAPI service.

This is one application in the [SentinelFlow](../../README.md) monorepo. It is
an independent educational project operating on synthetic data and makes no
real financial decisions.

## What it owns

| Concern                                              | Owner                    |
| ---------------------------------------------------- | ------------------------ |
| Feature engineering, model inference, model registry | this service             |
| Transaction persistence, outbox, alert lifecycle     | `apps/api` (Spring Boot) |

See [ADR-0002](../../docs/adr/0002-monorepo-and-service-boundaries.md).

## Local commands

`uv` provisions the interpreter and the environment; no system Python change is
needed, and none of these commands requires activating a virtual environment.

```bash
uv sync                            # create .venv and install from uv.lock
uv run python -m sentinelflow_scoring   # run the service
uv run pytest                      # tests
uv run ruff check .                # lint
uv run ruff format --check .       # formatting
uv run mypy                        # strict type check
```

## Endpoints

| Path            | Purpose                                                                   |
| --------------- | ------------------------------------------------------------------------- |
| `/health/live`  | Liveness. The process is running.                                         |
| `/health/ready` | Readiness. The process can do useful work, and whether a model is loaded. |
| `/info`         | Build identity: name, version, commit.                                    |
| `/metrics`      | Prometheus scrape.                                                        |
| `/docs`         | Generated OpenAPI documentation.                                          |

Scoring endpoints arrive in Phase 4.

## Configuration

Every setting is read from the environment with the prefix
`SENTINELFLOW_SCORING_` and validated at startup. An unknown variable with that
prefix is rejected rather than ignored, so a typo fails immediately instead of
silently leaving a default in place.

| Variable                         | Default   | Meaning                     |
| -------------------------------- | --------- | --------------------------- |
| `SENTINELFLOW_SCORING_HOST`      | `0.0.0.0` | Bind interface.             |
| `SENTINELFLOW_SCORING_PORT`      | `8000`    | HTTP port.                  |
| `SENTINELFLOW_SCORING_LOG_LEVEL` | `INFO`    | Root log level.             |
| `SENTINELFLOW_SCORING_GIT_SHA`   | `unknown` | Commit the build came from. |

## Python version

Exactly 3.13, not a floor with an open ceiling. numpy sets a `>=3.12` floor and
joblib — which model serialisation depends on from Phase 4 — declares support
only through 3.13. See `docs/research/RESEARCH_LOG.md` R-2026-08-25-06.
