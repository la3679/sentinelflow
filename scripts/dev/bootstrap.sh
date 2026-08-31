#!/usr/bin/env bash
#
# Verify prerequisites and generate safe local configuration.
#
# Idempotent: running it twice changes nothing the second time, and it never
# overwrites an existing .env. Generated secrets are local-only and git-ignored.
#
#   ./scripts/dev/bootstrap.sh          check and generate
#   ./scripts/dev/bootstrap.sh --check  check only, generate nothing
#
# Windows users without bash: scripts/dev/sf.ps1 bootstrap does the same thing
# natively in PowerShell.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

CHECK_ONLY=0
[ "${1:-}" = "--check" ] && CHECK_ONLY=1

RED=''
GREEN=''
YELLOW=''
RESET=''
if [ -t 1 ]; then
    RED=$'\033[31m'
    GREEN=$'\033[32m'
    YELLOW=$'\033[33m'
    RESET=$'\033[0m'
fi

failures=0
warnings=0

ok() { printf '  %sok%s    %s\n' "$GREEN" "$RESET" "$1"; }
warn() {
    printf '  %swarn%s  %s\n' "$YELLOW" "$RESET" "$1"
    warnings=$((warnings + 1))
}
fail() {
    printf '  %sFAIL%s  %s\n' "$RED" "$RESET" "$1"
    failures=$((failures + 1))
}

# ---------------------------------------------------------------------------
# Prerequisites
# ---------------------------------------------------------------------------
echo "SentinelFlow bootstrap"
echo
echo "Prerequisites:"

if command -v docker >/dev/null 2>&1; then
    if docker info >/dev/null 2>&1; then
        ok "docker $(docker version --format '{{.Server.Version}}' 2>/dev/null) - daemon reachable"
    else
        fail "docker is installed but the daemon is not reachable. Start Docker Desktop."
    fi
else
    fail "docker not found. Install Docker Desktop or Docker Engine."
fi

if docker compose version >/dev/null 2>&1; then
    ok "docker compose $(docker compose version --short 2>/dev/null)"
else
    fail "docker compose (v2 plugin) not found."
fi

if command -v bun >/dev/null 2>&1; then
    ok "bun $(bun --version)"
else
    fail "bun not found. See https://bun.sh - it is the only package manager this repository uses."
fi

if command -v uv >/dev/null 2>&1; then
    ok "uv $(uv --version | awk '{print $2}')"
else
    fail "uv not found. See https://docs.astral.sh/uv - it provisions Python 3.13 for apps/scoring."
fi

if command -v git >/dev/null 2>&1; then
    ok "git $(git --version | awk '{print $3}')"
else
    fail "git not found."
fi

# Java is only needed to build apps/api outside a container. `make up` builds it
# in Docker, so a missing JDK is a warning rather than a failure.
java_version=""
if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    java_version="$("${JAVA_HOME}/bin/java" -version 2>&1 | head -1 | sed 's/.*version "\([^"]*\)".*/\1/')"
elif command -v java >/dev/null 2>&1; then
    java_version="$(java -version 2>&1 | head -1 | sed 's/.*version "\([^"]*\)".*/\1/')"
fi

case "$java_version" in
25.*) ok "java $java_version" ;;
"") warn "no JDK found. Only needed to build apps/api outside Docker; 'make up' does not need it." ;;
*) warn "java $java_version - apps/api needs 25. Point JAVA_HOME at a JDK 25 to run './mvnw' directly." ;;
esac

# ---------------------------------------------------------------------------
# Local configuration
# ---------------------------------------------------------------------------
echo
echo "Local configuration:"

if [ ! -f .env.example ]; then
    fail ".env.example is missing - this is not a complete checkout."
fi

if [ -f .env ]; then
    missing=""
    # One key per line rather than a wrapped list. The wrapped version carried a
    # literal backslash-n, which the shell splits into a word named `n` - so
    # every run against an existing .env reported a missing secret called "n"
    # and exited 1. A continuation that is not a continuation is invisible until
    # something reads the output.
    required_secrets="POSTGRES_PASSWORD
GRAFANA_ADMIN_PASSWORD
SENTINELFLOW_JWT_SECRET
SENTINELFLOW_DEMO_OPERATOR_PASSWORD"
    for key in $required_secrets; do
        if ! grep -qE "^${key}=.+" .env; then
            missing="${missing} ${key}"
        fi
    done
    if [ -n "$missing" ]; then
        fail ".env exists but these required secrets are empty:${missing}"
        echo "        Fill them in, or delete .env and rerun to regenerate."
    else
        ok ".env exists with every required secret set - left untouched"
    fi
elif [ "$CHECK_ONLY" -eq 1 ]; then
    warn ".env does not exist. Run without --check to generate it."
elif [ "$failures" -gt 0 ]; then
    warn ".env not generated because a prerequisite failed."
else
    cp .env.example .env

    # openssl is present in Git Bash, WSL, macOS and every Linux image. Fall
    # back to /dev/urandom rather than to a weak or predictable value.
    gen_secret() {
        bytes="${1:-24}"
        if command -v openssl >/dev/null 2>&1; then
            openssl rand -base64 "$bytes"
        else
            head -c "$bytes" /dev/urandom | base64
        fi
    }

    pg_secret="$(gen_secret)"
    gf_secret="$(gen_secret)"
    # 48 bytes, because the API refuses a signing key under 32 characters and
    # base64 of 24 bytes is exactly 32 - too close to a limit to be generated
    # near it.
    jwt_secret="$(gen_secret 48)"
    op_secret="$(gen_secret)"

    # A '|' cannot appear in base64 output, so it is a safe sed delimiter here.
    sed -i.bak "s|^POSTGRES_PASSWORD=$|POSTGRES_PASSWORD=${pg_secret}|" .env
    sed -i.bak "s|^GRAFANA_ADMIN_PASSWORD=$|GRAFANA_ADMIN_PASSWORD=${gf_secret}|" .env
    sed -i.bak "s|^SENTINELFLOW_JWT_SECRET=$|SENTINELFLOW_JWT_SECRET=${jwt_secret}|" .env
    sed -i.bak         "s|^SENTINELFLOW_DEMO_OPERATOR_PASSWORD=$|SENTINELFLOW_DEMO_OPERATOR_PASSWORD=${op_secret}|" .env
    rm -f .env.bak

    ok ".env generated from .env.example with fresh local secrets"
    echo "        It is git-ignored. Do not commit it, and do not reuse these values anywhere else."
fi

# ---------------------------------------------------------------------------
# Result
# ---------------------------------------------------------------------------
echo
if [ "$failures" -gt 0 ]; then
    printf '%sBootstrap failed: %d prerequisite(s) missing.%s\n' "$RED" "$failures" "$RESET"
    exit 1
fi

if [ "$warnings" -gt 0 ]; then
    printf '%sBootstrap complete with %d warning(s).%s\n' "$YELLOW" "$warnings" "$RESET"
else
    printf '%sBootstrap complete.%s\n' "$GREEN" "$RESET"
fi

echo
echo "Next:  make up      start the stack"
echo "       make smoke   verify it is serving"
