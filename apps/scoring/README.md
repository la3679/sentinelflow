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

`contracts/openapi/sentinelflow-scoring.yaml` is authoritative. It is an
**internal** contract between `apps/api` and this service — not reachable from a
browser, and not part of the public `/api/v1` surface.

| Path             | Purpose                                                                   |
| ---------------- | ------------------------------------------------------------------------- |
| `POST /v1/score` | Score one transaction and a bounded account context.                      |
| `GET /v1/model`  | Which model is loaded, and what it was measured at.                       |
| `/health/live`   | Liveness. The process is running.                                         |
| `/health/ready`  | Readiness. The process can do useful work, and whether a model is loaded. |
| `/info`          | Build identity: name, version, commit.                                    |
| `/metrics`       | Prometheus scrape.                                                        |
| `/docs`          | Generated OpenAPI documentation.                                          |

`POST /v1/score` has **no side effects**. It writes nothing and remembers nothing
between requests, so the same transaction returns the same score under the same
model and feature version, a retry is free, and a duplicate is harmless.

It returns a score and the reasons for it. **It never decides whether an alert
should exist** — alerting policy is versioned configuration in `apps/api`, because
a threshold is a business decision on a different schedule from a model and has to
mean the same thing whether or not the model answered at all
([ADR-0008](../../docs/adr/0008-scoring-service-boundary.md) §4).

### Reason codes

`modelScore` comes with a bounded, most-significant-first list of contributions.
For the served logistic regression a contribution is not an approximation of the
model — it is the model taken apart: `coefficient x standardised value`, on the
log-odds scale before calibration. Calibration is monotone, so the contributions
explain the **ranking**; they do not sum to the 0-to-100 score, and the contract
says as much.

A continuous feature carries a direction (`VELOCITY_1M_HIGH`, `AMOUNT_RATIO_LOW`).
An indicator does not, and is reported **only when it fired** — `NEW_DEVICE` on a
device the account has always used would be an explanation that says the opposite
of what happened. A model that cannot be decomposed returns an empty `reasons`
list and a warning saying why, because an invented explanation is worse than an
absent one.

### Errors

RFC 9457 `application/problem+json`, and never a traceback, a file path, or an
echo of the input. A 422 names the fields that failed and says nothing about what
was in them: on this service the offending value is a transaction amount, a device
reference or an account handle.

`X-Correlation-Id` is accepted and echoed on every response. A value that does not
parse as a UUID is replaced rather than reflected.

## Which model gets served

The service loads one registry entry at startup, or it does not start.

| Situation                                          | Behaviour                                                                                   |
| -------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| Exactly one entry at the running feature version   | Loaded and served.                                                                          |
| No entry at all                                    | Runs. `modelLoaded: false`, `/v1/score` and `/v1/model` 503, and the API degrades to rules. |
| Two or more entries at that feature version        | **Refuses to start.** Pin one, or remove the superseded entry.                              |
| Entry at another feature version                   | Skipped. This build cannot compute the columns it was fitted on.                            |
| Checksum, feature version or column order mismatch | **Refuses to start.**                                                                       |

The last row is the one worth stating plainly: a model handed its columns in a
different order is not a broken model, it is one quietly answering about different
quantities, and no error, warning or downstream check would ever notice. That is
why it is a refusal.

`apps/scoring/models/` is committed
([ADR-0010](../../docs/adr/0010-model-selection-and-evaluation.md) §6) and copied
into the image, so a demo scores without anyone running `make train` first.

## Configuration

Every setting is read from the environment with the prefix
`SENTINELFLOW_SCORING_` and validated at startup. An unknown variable with that
prefix is rejected rather than ignored, so a typo fails immediately instead of
silently leaving a default in place.

| Variable                             | Default   | Meaning                                           |
| ------------------------------------ | --------- | ------------------------------------------------- |
| `SENTINELFLOW_SCORING_HOST`          | `0.0.0.0` | Bind interface.                                   |
| `SENTINELFLOW_SCORING_PORT`          | `8000`    | HTTP port.                                        |
| `SENTINELFLOW_SCORING_LOG_LEVEL`     | `INFO`    | Root log level.                                   |
| `SENTINELFLOW_SCORING_GIT_SHA`       | `unknown` | Commit the build came from.                       |
| `SENTINELFLOW_SCORING_MODELS_ROOT`   | `models`  | Registry root, relative to the working directory. |
| `SENTINELFLOW_SCORING_MODEL_NAME`    | unset     | Pin the entry by name instead of discovering it.  |
| `SENTINELFLOW_SCORING_MODEL_VERSION` | unset     | Pin the entry by version.                         |

The two pin variables are set together or not at all: half a pin names a directory
of versions, and choosing one of them is the guess the setting exists to avoid.

## Python version

Exactly 3.13, not a floor with an open ceiling. numpy sets a `>=3.12` floor and
joblib — which model serialisation depends on from Phase 4 — declares support
only through 3.13. See `docs/research/RESEARCH_LOG.md` R-2026-08-25-06.
