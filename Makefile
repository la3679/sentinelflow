# SentinelFlow developer command surface.
#
# Every target here is a thin, readable wrapper. The two with real logic -
# bootstrap and smoke - live in scripts/ so that Windows users without make can
# run exactly the same code:
#
#   Linux, macOS, WSL, Git Bash   make <target>
#   Windows PowerShell            .\scripts\dev\sf.ps1 <target>
#
# Run `make help` for the list.

SHELL := /bin/bash
.DEFAULT_GOAL := help

COMPOSE ?= docker compose
BUN     ?= bun
UV      ?= uv
MVNW    ?= ./mvnw

WEB     := apps/web
API     := apps/api
SCORING := apps/scoring

.PHONY: help bootstrap up down logs ps reset-demo seed export-dataset train replay \
        build test test-web test-api test-scoring test-integration test-e2e \
        lint format format-check security smoke docs-check contracts-check clean

## ---------------------------------------------------------------------------
## Help
## ---------------------------------------------------------------------------

help: ## Show this help
	@echo "SentinelFlow - available targets:"
	@echo
	@grep -hE '^[a-zA-Z0-9_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'
	@echo
	@echo "Windows PowerShell users: .\\scripts\\dev\\sf.ps1 <target>"

## ---------------------------------------------------------------------------
## Local environment
## ---------------------------------------------------------------------------

bootstrap: ## Verify prerequisites and generate local .env (idempotent)
	@./scripts/dev/bootstrap.sh

up: ## Start the full local stack and wait until every service is healthy
	@$(COMPOSE) up -d --build --wait --wait-timeout 420

down: ## Stop the stack, keeping durable volumes
	@$(COMPOSE) down

logs: ## Follow logs for every service
	@$(COMPOSE) logs -f

ps: ## Show the status of every service
	@$(COMPOSE) ps

reset-demo: ## DESTRUCTIVE - stop the stack and delete all local data volumes
	@echo "This deletes the PostgreSQL, Kafka, Prometheus and Grafana volumes."
	@echo "All local demo data will be lost. This cannot be undone."
	@read -r -p "Type 'reset' to confirm: " reply; \
	 if [ "$$reply" = "reset" ]; then \
	   $(COMPOSE) down -v; \
	   echo "Volumes deleted. Run 'make up' for a clean stack."; \
	 else \
	   echo "Aborted. Nothing was deleted."; \
	   exit 1; \
	 fi

# Seeding runs at API startup behind SENTINELFLOW_SEED_ENABLED, so this
# recreates that one service with the flag set and then recreates it again
# without. Two recreates rather than one, deliberately: leaving the flag on
# would make every later restart re-run the seed, and a service that reseeds
# whenever it happens to restart is one nobody can leave running.
#
# Both halves are idempotent - the party loader skips a database that already
# holds demo customers, and every generated idempotency key is derived from the
# seed, so the unique constraint rejects a second load. Running this twice is a
# no-op rather than a doubled dataset.
seed: ## Generate and load deterministic demo data
	@echo "Seeding with profile $${SENTINELFLOW_SEED_PROFILE:-DEMO}, seed $${SENTINELFLOW_SEED:-20260826}."
	@SENTINELFLOW_SEED_ENABLED=true $(COMPOSE) up -d --force-recreate --wait --wait-timeout 300 api
	@$(COMPOSE) logs api | grep -E "Seed complete|Scenario load complete|seed skipped|load skipped" || true
	@echo "Returning the API to its unseeded configuration."
	@$(COMPOSE) up -d --force-recreate --wait --wait-timeout 300 api
	@echo "Done. Re-running this is a no-op: both loaders are idempotent."

export-dataset: ## Export the labelled training dataset (ADR-0010)
	@echo "Exporting the labelled training dataset for seed $${SENTINELFLOW_SEED:-20260826}, profile $${SENTINELFLOW_SEED_PROFILE:-DEMO}."
	@mkdir -p data/generated/training
	@SENTINELFLOW_SCORING_EXPORT_ENABLED=true $(COMPOSE) up -d --force-recreate --wait --wait-timeout 300 api
	@$(COMPOSE) logs api | grep -E "Training export complete|had no stored row" || true
	@echo "Returning the API to its normal configuration."
	@$(COMPOSE) up -d --force-recreate --wait --wait-timeout 300 api
# The second recreate is what takes the export flag back off the service, and a
# previous session found the API in a crash loop because it had not taken
# effect: the flag was still set, the export runner correctly refused to
# overwrite an existing dataset, and the container failed startup and restarted
# for ever. Asserting it here turns a silent bad state into a message that says
# what to do.
	@if $(COMPOSE) ps --format '{{.Service}} {{.Status}}' | grep -q '^api .*Restarting'; then \
	   echo "The api container is restarting; the export flag is probably still set."; \
	   echo "Re-run: docker compose up -d --force-recreate --wait api"; \
	   exit 1; \
	 fi
	@echo "Written to data/generated/training/ - git-ignored, regenerate rather than commit."

train: ## Train, evaluate and register a risk model (ADR-0010)
	@echo "Training from data/generated/training. Offline, never an API side effect."
	@cd $(SCORING) && $(UV) run python -m sentinelflow_scoring.training

# The operational scenarios from section 8.3, which nothing else produces: a
# temporary scoring-service outage and a malformed event reaching the
# dead-letter path. The transaction *shapes* - velocity, amount spikes, card
# testing, drains, off-hours - are generated by `make seed` and replaying them
# here would be a second implementation of something that exists.
#
# The HTTP endpoint section 10 lists is a different thing on a different
# schedule: it is API surface needing authorization and rate limiting, and
# shipping an unbounded replay endpoint with no role behind it to satisfy a
# Makefile target would be the wrong trade.
replay: ## Replay an operational scenario against the running stack
	@./scripts/dev/replay.sh $${SCENARIO:-all}

## ---------------------------------------------------------------------------
## Build and test
## ---------------------------------------------------------------------------

build: ## Build every application
	@$(BUN) install --frozen-lockfile
	@cd $(WEB) && $(BUN) run build
	@cd $(API) && $(MVNW) -B -DskipTests package
	@cd $(SCORING) && $(UV) sync --frozen

test: test-web test-api test-scoring ## Run every standard test suite

test-web: ## Unit tests for the console
	@cd $(WEB) && $(BUN) run test

# Unit tests only. `make test` must run without Docker, so the Testcontainers
# suites are excluded here and have their own target below. JaCoCo is skipped
# with them: the coverage threshold is set for a full run, and half a run
# cannot be judged against it.
test-api: ## Tests for the Spring Boot service
	@cd $(API) && $(MVNW) -B verify -DskipITs -Djacoco.skip=true

test-scoring: ## Tests for the scoring service
	@cd $(SCORING) && $(UV) run pytest

# Needs a running Docker engine. skipUnitTests is a property of our own because
# Surefire and Failsafe share the built-in skipTests, so there is no standard
# way to skip only the fast half.
test-integration: ## Testcontainers PostgreSQL suites (requires Docker)
	@cd $(API) && $(MVNW) -B verify -DskipUnitTests=true

test-e2e: ## Playwright browser, accessibility and responsive checks
	@cd $(WEB) && $(BUN) run build && $(BUN) run test:e2e

## ---------------------------------------------------------------------------
## Quality gates
## ---------------------------------------------------------------------------

lint: ## Lint every application
	@cd $(WEB) && $(BUN) run lint
	@cd $(SCORING) && $(UV) run ruff check .
	@cd $(SCORING) && $(UV) run mypy
	@cd $(API) && $(MVNW) -B -q spotless:check

format: ## Format everything in place
	@$(BUN) run format
	@cd $(SCORING) && $(UV) run ruff format .
	@cd $(API) && $(MVNW) -B -q spotless:apply

format-check: ## Check formatting without changing anything
	@$(BUN) run format:check
	@cd $(SCORING) && $(UV) run ruff format --check .
	@cd $(API) && $(MVNW) -B -q spotless:check

security: ## Scan the repository for committed secrets
	@if command -v gitleaks >/dev/null 2>&1; then \
	  gitleaks detect --source . --redact --verbose; \
	else \
	  echo "gitleaks is not installed locally; running it through Docker."; \
	  docker run --rm -v "$$PWD:/repo" zricethezav/gitleaks:latest \
	    detect --source /repo --redact --verbose; \
	fi

smoke: ## Verify the running stack actually serves
	@./scripts/smoke/smoke.sh

docs-check: ## Check documentation formatting, links, and placeholders
	@$(BUN) run format:check
	@$(BUN) scripts/dev/check-docs.mjs

contracts-check: ## Validate OpenAPI, AsyncAPI, and the event schemas
	@$(BUN) scripts/dev/check-contracts.mjs

## ---------------------------------------------------------------------------
## Housekeeping
## ---------------------------------------------------------------------------

clean: ## Remove build output (keeps dependencies and Docker volumes)
	@rm -rf $(WEB)/dist $(WEB)/coverage $(WEB)/test-results $(WEB)/playwright-report
	@rm -rf $(API)/target
	@rm -rf $(SCORING)/.pytest_cache $(SCORING)/.mypy_cache $(SCORING)/.ruff_cache $(SCORING)/.coverage
	@echo "Build output removed."
