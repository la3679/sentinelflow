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

.PHONY: help bootstrap up down logs ps reset-demo seed replay \
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

# Not implemented yet, and deliberately loud about it rather than silently
# succeeding. The deterministic synthetic generator these drive is Phase 4.
seed: ## (Phase 4) Generate and load deterministic demo data
	@echo "make seed is not implemented yet."
	@echo "The deterministic synthetic generator it drives is delivered in Phase 4."
	@echo "See docs/planning/IMPLEMENTATION_PLAN.md."
	@exit 1

replay: ## (Phase 4) Replay the default synthetic scenario
	@echo "make replay is not implemented yet."
	@echo "Scenario replay is delivered in Phase 4."
	@echo "See docs/planning/IMPLEMENTATION_PLAN.md."
	@exit 1

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
