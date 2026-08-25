#!/usr/bin/env bash
#
# Smoke-test the running local stack.
#
# Asks each service to do the thing it exists to do, over the network, exactly
# as an operator would. It does not read configuration and conclude that the
# stack must therefore work.
#
# Requires the stack to be up:  make up && make smoke
#
# Windows users without bash: scripts/dev/sf.ps1 smoke does the same thing
# natively in PowerShell.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

# Ports follow .env when it exists, so a developer who moved a port off a
# conflict still gets a working smoke test.
if [ -f .env ]; then
    set -a
    # shellcheck disable=SC1091
    . ./.env
    set +a
fi

API_PORT="${API_PORT:-8080}"
SCORING_PORT="${SCORING_PORT:-8000}"
WEB_PORT="${WEB_PORT:-5173}"
PROMETHEUS_PORT="${PROMETHEUS_PORT:-9090}"
GRAFANA_PORT="${GRAFANA_PORT:-3000}"

RED=''
GREEN=''
RESET=''
if [ -t 1 ]; then
    RED=$'\033[31m'
    GREEN=$'\033[32m'
    RESET=$'\033[0m'
fi

passed=0
failed=0

pass() {
    printf '  %spass%s  %s\n' "$GREEN" "$RESET" "$1"
    passed=$((passed + 1))
}
fail() {
    printf '  %sFAIL%s  %s\n' "$RED" "$RESET" "$1"
    [ -n "${2:-}" ] && printf '        %s\n' "$2"
    failed=$((failed + 1))
}

# Assert that a URL returns an expected status, and optionally that its body
# contains a string.
check_http() {
    local label="$1" url="$2" expect_status="$3" expect_body="${4:-}"
    local body_file status

    # The body goes to a file rather than into a variable. A static asset can
    # contain a NUL byte, and command substitution silently drops those while
    # printing a shell warning on every call.
    body_file="$(mktemp)"
    status="$(curl -sS --compressed --max-time 10 -o "$body_file" -w '%{http_code}' "$url" 2>/dev/null)" || {
        rm -f "$body_file"
        fail "$label" "request failed - is the stack up? (make up)"
        return
    }

    if [ "$status" != "$expect_status" ]; then
        rm -f "$body_file"
        fail "$label" "expected HTTP $expect_status, got $status"
        return
    fi
    if [ -n "$expect_body" ] && ! grep -qa -- "$expect_body" "$body_file"; then
        rm -f "$body_file"
        fail "$label" "response did not contain '$expect_body'"
        return
    fi
    rm -f "$body_file"
    pass "$label"
}

echo "SentinelFlow smoke test"
echo

# ---------------------------------------------------------------------------
# Every service reports healthy to Docker
# ---------------------------------------------------------------------------
echo "Container health:"
for service in postgres kafka scoring api web prometheus grafana; do
    cid="$(docker compose ps -q "$service" 2>/dev/null)"
    if [ -z "$cid" ]; then
        fail "$service" "not running - is the stack up? (make up)"
        continue
    fi
    state="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' "$cid" 2>/dev/null)"
    if [ "$state" = "healthy" ]; then
        pass "$service is healthy"
    else
        fail "$service" "health status is '$state'"
    fi
done

# ---------------------------------------------------------------------------
# Each service answers over HTTP
# ---------------------------------------------------------------------------
echo
echo "Service endpoints:"
check_http "api readiness"          "http://127.0.0.1:${API_PORT}/actuator/health/readiness"   200 '"status":"UP"'
check_http "api liveness"           "http://127.0.0.1:${API_PORT}/actuator/health/liveness"    200 '"status":"UP"'
check_http "api metrics"            "http://127.0.0.1:${API_PORT}/actuator/prometheus"         200 'application="sentinelflow-api"'
check_http "scoring readiness"      "http://127.0.0.1:${SCORING_PORT}/health/ready"            200 '"status":"UP"'
check_http "scoring build identity" "http://127.0.0.1:${SCORING_PORT}/info"                    200 'sentinelflow-scoring'
check_http "scoring metrics"        "http://127.0.0.1:${SCORING_PORT}/metrics"                 200 'python_info'
check_http "console shell"          "http://127.0.0.1:${WEB_PORT}/"                            200 'SentinelFlow'
check_http "console deep link"      "http://127.0.0.1:${WEB_PORT}/alerts/ALT-0007"             200 'SentinelFlow'
check_http "prometheus"             "http://127.0.0.1:${PROMETHEUS_PORT}/-/healthy"            200
check_http "grafana"                "http://127.0.0.1:${GRAFANA_PORT}/api/health"              200 '"database"'

# ---------------------------------------------------------------------------
# Endpoints that must NOT be reachable
# ---------------------------------------------------------------------------
echo
echo "Closed by design:"
check_http "api /actuator/env is closed"   "http://127.0.0.1:${API_PORT}/actuator/env"   404
check_http "api /actuator/beans is closed" "http://127.0.0.1:${API_PORT}/actuator/beans" 404

# ---------------------------------------------------------------------------
# The pieces are actually wired to each other
# ---------------------------------------------------------------------------
echo
echo "Wiring:"

targets="$(curl -sS --max-time 10 "http://127.0.0.1:${PROMETHEUS_PORT}/api/v1/targets?state=active" 2>/dev/null)"
for job in sentinelflow-api sentinelflow-scoring; do
    if printf '%s' "$targets" | grep -q "\"job\":\"${job}\""; then
        if printf '%s' "$targets" | tr ',' '\n' | grep -A20 "\"job\":\"${job}\"" | grep -q '"health":"down"'; then
            fail "prometheus scrapes $job" "target is down"
        else
            pass "prometheus scrapes $job"
        fi
    else
        fail "prometheus scrapes $job" "no such active target"
    fi
done

if docker compose exec -T postgres pg_isready -U "${POSTGRES_USER:-sentinelflow}" -d "${POSTGRES_DB:-sentinelflow}" >/dev/null 2>&1; then
    pass "postgres accepts connections to ${POSTGRES_DB:-sentinelflow}"
else
    fail "postgres accepts connections" "pg_isready failed"
fi

# Create, describe and delete a throwaway topic. A broker that answers an admin
# request is proven to be serving in a way that an open port is not.
# MSYS_NO_PATHCONV is scoped to these calls rather than exported. Git Bash
# rewrites an argument that looks like an absolute POSIX path into a Windows
# path before handing it to a native binary, which turns
# /opt/kafka/bin/kafka-topics.sh into a path inside the Git installation. It
# cannot be exported, because curl above needs the ordinary conversion to write
# its temporary output file.
smoke_topic="sentinelflow-smoke-$$"
kafka_topics() {
    MSYS_NO_PATHCONV=1 docker compose exec -T kafka         /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 "$@"
}

if kafka_topics --create --topic "$smoke_topic" --partitions 1 --replication-factor 1 >/dev/null 2>&1; then
    kafka_topics --delete --topic "$smoke_topic" >/dev/null 2>&1
    pass "kafka accepts topic creation and deletion"
else
    fail "kafka accepts topic creation" "topic admin request failed"
fi

# ---------------------------------------------------------------------------
# Result
# ---------------------------------------------------------------------------
echo
if [ "$failed" -gt 0 ]; then
    printf '%s%d passed, %d FAILED.%s\n' "$RED" "$passed" "$failed" "$RESET"
    exit 1
fi
printf '%s%d passed, 0 failed.%s\n' "$GREEN" "$passed" "$RESET"
