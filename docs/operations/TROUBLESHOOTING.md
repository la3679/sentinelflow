# Troubleshooting

Symptoms a developer actually hits while running SentinelFlow locally, and the fix for each. For a
signal that is firing on the running stack rather than a command that will not run, go to
[`RUNBOOKS.md`](RUNBOOKS.md).

## Starting the stack

| Symptom                                                   | Cause and fix                                                                                                                                                                                |
| --------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `make up` fails on `POSTGRES_PASSWORD`                    | `.env` is missing or the secret is blank. Run `make bootstrap`.                                                                                                                              |
| Compose refuses to start on the token-signing secret      | Deliberate. There is no guessable fallback for `SENTINELFLOW_JWT_SECRET`; `make bootstrap` generates one.                                                                                    |
| A port is already in use                                  | Change it in `.env` — every published port is a variable. Change `VITE_API_BASE_URL` and the CORS list to match if you move `API_PORT` or `WEB_PORT`.                                        |
| PostgreSQL refuses to start after a version change        | The volume was formatted by a different major version. `make reset-demo`.                                                                                                                    |
| Kafka refuses to start after the cluster id was edited    | Same cause. `make reset-demo`.                                                                                                                                                               |
| The API exits on startup complaining about reference data | The `system` principal is missing from `users`. V1 inserts it and the seed does not write it, so a truncate of `users` deletes a row nothing puts back. `make reset-demo`, then `make seed`. |

## Running the tooling

| Symptom                                            | Cause and fix                                                                                                                                                                                                                                                                          |
| -------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `./mvnw` fails with permission denied              | The executable bit was lost. `git update-index --chmod=+x apps/api/mvnw`.                                                                                                                                                                                                              |
| Playwright times out on every test                 | A stale `vite preview` is holding port 4173. Kill it and rerun.                                                                                                                                                                                                                        |
| `bun install` fails with `ENAMETOOLONG` on Windows | The clone path is too deep — nested `node_modules` paths exceed Windows' 260-character limit. Clone somewhere shorter, such as `C:\src\sentinelflow`.                                                                                                                                  |
| `make smoke` fails on Kafka in Git Bash            | Path conversion. The script scopes `MSYS_NO_PATHCONV`; run the script rather than the commands by hand.                                                                                                                                                                                |
| `make` is not installed                            | Every target has a PowerShell equivalent: `.\scripts\dev\sf.ps1 <target>`.                                                                                                                                                                                                             |
| The API container is stuck in `Restarting`         | `SENTINELFLOW_SCORING_EXPORT_ENABLED` is still set on the service from a `make export-dataset`, and the export runner refuses to overwrite an existing dataset — so startup fails, restarts, and fails again. Run `docker compose up -d --force-recreate --wait api` without the flag. |

## The pipeline is running but nothing is scored

| Symptom                                                | Cause and fix                                                                                                                                                                              |
| ------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Every assessment is `degraded`                         | Check the scoring service's log for `Unsupported upgrade request` beside each rejection **before looking anywhere else**. Runbook 4 in [`RUNBOOKS.md`](RUNBOOKS.md) has the full sequence. |
| Every service is healthy and no message is published   | Check that the Kafka topics exist. They are created explicitly by a one-shot service the API waits for, not by auto-creation, which is disabled (ADR-0006 §3).                             |
| `POST /transactions` answers `401`                     | The endpoint requires `X-API-Key` — it is a machine-to-machine surface with its own credential (ADR-0017 §1). The value is in `.env`.                                                      |
| `POST /transactions` answers `429`                     | The rate limiter. The allowance is per API instance and configured in `application.yaml`; see [ADR-0017 §2](../adr/0017-protecting-the-ingestion-surface.md).                              |
| An operator endpoint answers `401` after a page reload | Expected. The console holds its token in the tab's memory and writes nothing to browser storage, so a reload signs you out ([ADR-0012 §3](../adr/0012-operator-authentication.md)).        |
| The browser reports a CORS failure                     | The console and the API are separate origins. The API answers a browser only from `SENTINELFLOW_CORS_ALLOWED_ORIGINS` ([ADR-0013](../adr/0013-console-to-api-cross-origin-access.md)).     |

## Getting back to a clean database

`make reset-demo` deletes the volumes, recreates the database, re-runs the migrations, and asks you
to type `reset` first. Follow it with `make seed`.

**Do not truncate `users` to shrink a dataset in place.** It holds the `system` principal, which the
first migration inserts as reference data and the seed does not write, so truncating it deletes a
row nothing puts back and the API then refuses to start. If tables must be cleared in place, leave
`users` and `user_roles` alone and truncate only
`customers, accounts, merchants, transactions, outbox_events, processed_events CASCADE`.
