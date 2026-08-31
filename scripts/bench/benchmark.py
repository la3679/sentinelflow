#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Measure this stack, and record what the measurement is worth.

Phase 9's benchmark. It drives the running compose stack over HTTP, exactly as a
caller would, and writes both a JSON record and a Markdown report.

Three rules it follows, because a benchmark that breaks them produces numbers
worse than none:

**It records the machine.** A latency figure without the hardware, the container
runtime and the dataset it was measured against is not reproducible and is not
evidence. Everything in "Reference environment" is read from the running system
rather than typed in.

**It does not fight the rate limiter, and does not pretend it is not there.**
Reads are paced below the configured allowance, because what is being measured
is latency and pacing does not change it. Ingestion runs one burst, because the
burst is what the limiter permits at once; the sustained ceiling is a
configured policy (ADR-0017 §2) rather than a property of the pipeline, and the
report says so instead of quietly raising it.

**It says what it did not measure.** Every section carries its own limits.

Standard library only. It runs under `uv run --no-project --python 3.13`, so it
needs nothing installed and pins the interpreter ADR-0004 already chose.
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import subprocess
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field, asdict
from datetime import datetime, timezone

DEFAULT_BASE = "http://127.0.0.1:8080"

# Under the `standard` burst of 60, and paced, so the limiter never shapes a
# read measurement. Latency is what these answer; throughput is not.
READ_ITERATIONS = 30
READ_PACE_SECONDS = 0.25

# The `ingest` burst is 120. Staying under it means the numbers describe the
# pipeline rather than the policy in front of it.
INGEST_REQUESTS = 100
INGEST_CONCURRENCY = 8

# How long to wait for the consumer, the scoring call and the assessment write.
ASSESSMENT_TIMEOUT_SECONDS = 120


@dataclass
class Sample:
    """One endpoint's latency distribution, in milliseconds."""

    name: str
    count: int
    ok: int
    failed: int
    p50: float
    p95: float
    p99: float
    minimum: float
    maximum: float
    statuses: dict[str, int] = field(default_factory=dict)


def percentile(values: list[float], fraction: float) -> float:
    """The nearest-rank percentile.

    Not interpolated. With thirty samples an interpolated p99 invents a value
    between two observations, and every number in this report is meant to be one
    that actually happened.
    """
    if not values:
        return 0.0
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, int(round(fraction * len(ordered) + 0.5)) - 1))
    return ordered[index]


def summarise(name: str, timings: list[float], statuses: dict[str, int], failures: int) -> Sample:
    return Sample(
        name=name,
        count=len(timings) + failures,
        ok=len(timings),
        failed=failures,
        p50=round(percentile(timings, 0.50), 2),
        p95=round(percentile(timings, 0.95), 2),
        p99=round(percentile(timings, 0.99), 2),
        minimum=round(min(timings), 2) if timings else 0.0,
        maximum=round(max(timings), 2) if timings else 0.0,
        statuses=statuses,
    )


def request(
    method: str,
    url: str,
    *,
    body: dict | None = None,
    headers: dict[str, str] | None = None,
    timeout: float = 30.0,
) -> tuple[int, bytes, float]:
    """One request. Returns the status, the body, and the elapsed milliseconds.

    A non-2xx is a result rather than an exception: a 429 or a 401 is data about
    the system, and raising on it would lose the measurement that explains it.
    """
    payload = json.dumps(body).encode("utf-8") if body is not None else None
    prepared = urllib.request.Request(url, data=payload, method=method)
    prepared.add_header("Accept", "application/json")
    if payload is not None:
        prepared.add_header("Content-Type", "application/json")
    for key, value in (headers or {}).items():
        prepared.add_header(key, value)

    started = time.perf_counter()
    try:
        with urllib.request.urlopen(prepared, timeout=timeout) as response:
            content = response.read()
            return response.status, content, (time.perf_counter() - started) * 1000
    except urllib.error.HTTPError as failure:
        content = failure.read()
        return failure.code, content, (time.perf_counter() - started) * 1000


def shell(command: list[str], timeout: float = 60.0, *, required: bool = False) -> str:
    """A command's stdout, trimmed.

    **`required` is the whole point of this signature.** Environment discovery
    must never fail the benchmark — a missing `docker --version` is a gap in the
    report, not a reason to lose the measurements. A query whose result becomes
    a number in that report is the opposite: swallowing its failure produces an
    empty section that reads like a measured zero. The first draft of this file
    did exactly that, with a column name that does not exist, and reported
    nothing rather than saying so.
    """
    try:
        finished = subprocess.run(
            command, capture_output=True, text=True, timeout=timeout, check=False
        )
    except (OSError, subprocess.SubprocessError) as failure:
        if required:
            raise SystemExit(f"{' '.join(command[:3])} could not be run: {failure}") from failure
        return ""

    if required and finished.returncode != 0:
        raise SystemExit(
            f"{' '.join(command[:3])} failed with {finished.returncode}: "
            f"{finished.stderr.strip()[:500]}"
        )
    return finished.stdout.strip()


def psql(sql: str, *, required: bool = True) -> str:
    """A query against the demo database. Required by default, for the reason above."""
    return shell(
        [
            "docker",
            "compose",
            "exec",
            "-T",
            "postgres",
            "psql",
            "-U",
            os.environ.get("POSTGRES_USER", "sentinelflow"),
            "-d",
            os.environ.get("POSTGRES_DB", "sentinelflow"),
            "-At",
            "--set",
            "ON_ERROR_STOP=1",
            "-c",
            sql,
        ],
        required=required,
    )


def reference_environment() -> dict:
    """The machine, the runtime and the dataset, all read rather than typed."""
    counts = {}
    rows = psql(
        "SELECT 'transactions', count(*) FROM transactions"
        " UNION ALL SELECT 'risk_assessments', count(*) FROM risk_assessments"
        " UNION ALL SELECT 'alerts', count(*) FROM alerts"
        " UNION ALL SELECT 'accounts', count(*) FROM accounts"
        " UNION ALL SELECT 'customers', count(*) FROM customers"
        " UNION ALL SELECT 'merchants', count(*) FROM merchants"
        " UNION ALL SELECT 'outbox_events', count(*) FROM outbox_events"
    )
    for line in rows.splitlines():
        if "|" in line:
            table, value = line.split("|", 1)
            counts[table] = int(value)

    return {
        "measuredAtUtc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "os": f"{platform.system()} {platform.release()}",
        "machine": platform.machine(),
        "processors": os.cpu_count(),
        "python": platform.python_version(),
        "docker": shell(["docker", "--version"]),
        "dockerCompose": shell(["docker", "compose", "version", "--short"]),
        "postgres": (psql("SHOW server_version").splitlines() or [""])[0],
        "images": shell(
            ["docker", "compose", "images", "--format", "{{.Service}} {{.Repository}}:{{.Tag}}"]
        ),
        "gitSha": shell(["git", "rev-parse", "--short", "HEAD"]),
        "datasetRows": counts,
    }


def sign_in(base: str, username: str, password: str) -> str:
    status, body, _ = request(
        "POST", f"{base}/api/v1/auth/login", body={"username": username, "password": password}
    )
    if status != 200:
        raise SystemExit(
            f"Sign-in failed with {status}. The read benchmarks need an operator token.\n"
            f"Set SENTINELFLOW_DEMO_OPERATOR_PASSWORD, or pass --password.\n{body[:300]!r}"
        )
    return json.loads(body)["token"]


def reference(sql: str, label: str) -> str:
    value = psql(sql)
    first = value.splitlines()[0] if value else ""
    if not first:
        raise SystemExit(f"No {label} found in the database. Run `make seed` first.")
    return first


def transaction_body(key: str, account: str, merchant: str, amount: str) -> dict:
    return {
        "idempotencyKey": key,
        "accountReference": account,
        "merchantReference": merchant,
        "type": "PURCHASE",
        "channel": "CARD_NOT_PRESENT",
        "amount": {"value": amount, "currency": "GBP"},
        "originCountry": "GB",
        "occurredAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }


def measure_reads(base: str, token: str) -> list[Sample]:
    """Latency for the endpoints an operator's console actually calls.

    Paced deliberately. The `standard` allowance is 300 a minute with a burst of
    60, and a tight loop would spend the burst and start measuring the limiter's
    refusals instead of the query behind them. Pacing does not change a latency
    distribution; it only makes this take longer.
    """
    headers = {"Authorization": f"Bearer {token}"}
    endpoints = [
        ("GET /alerts (page 1, size 20)", "/api/v1/alerts?page=0&size=20"),
        ("GET /transactions (page 1, size 50)", "/api/v1/transactions?page=0&size=50"),
        ("GET /transactions (page 20, size 50)", "/api/v1/transactions?page=20&size=50"),
        ("GET /reports/alert-summary (24h)", "/api/v1/reports/alert-summary" + window(1)),
        ("GET /reports/alert-summary (30d)", "/api/v1/reports/alert-summary" + window(30)),
    ]

    samples = []
    for name, path in endpoints:
        timings: list[float] = []
        statuses: dict[str, int] = {}
        failures = 0
        for _ in range(READ_ITERATIONS):
            status, _, elapsed = request("GET", base + path, headers=headers)
            statuses[str(status)] = statuses.get(str(status), 0) + 1
            if 200 <= status < 300:
                timings.append(elapsed)
            else:
                failures += 1
            time.sleep(READ_PACE_SECONDS)
        samples.append(summarise(name, timings, statuses, failures))
    return samples


def window(days: int) -> str:
    now = datetime.now(timezone.utc)
    start = now.timestamp() - days * 86400
    frm = datetime.fromtimestamp(start, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    to = now.strftime("%Y-%m-%dT%H:%M:%SZ")
    return f"?from={frm}&to={to}"


def measure_ingestion(
    base: str, api_key: str, account: str, merchant: str, requests_count: int, concurrency: int
) -> tuple[Sample, dict, list[str]]:
    """One burst through the ingestion endpoint.

    A burst rather than a sustained rate, and that is the honest shape: the
    limiter permits 120 at once and 600 a minute after that, so a sustained
    measurement would be measuring a configured policy rather than the pipeline
    (ADR-0017 §2). What this answers is what one accepted transaction costs and
    how the endpoint behaves under eight concurrent callers.
    """
    run = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")
    keys = [f"bench-{run}-{index:05d}" for index in range(requests_count)]
    headers = {"X-API-Key": api_key}

    def post(key: str) -> tuple[int, float, str]:
        status, body, elapsed = request(
            "POST",
            f"{base}/api/v1/transactions",
            body=transaction_body(key, account, merchant, "1249.99"),
            headers=headers,
        )
        return status, elapsed, key

    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        results = list(pool.map(post, keys))
    wall = time.perf_counter() - started

    timings = [elapsed for status, elapsed, _ in results if status in (200, 202)]
    statuses: dict[str, int] = {}
    for status, _, _ in results:
        statuses[str(status)] = statuses.get(str(status), 0) + 1
    accepted = [key for status, _, key in results if status in (200, 202)]

    throughput = {
        "wallClockSeconds": round(wall, 3),
        "acceptedPerSecond": round(len(accepted) / wall, 1) if wall > 0 else 0.0,
        "concurrency": concurrency,
        "rateLimited": statuses.get("429", 0),
    }
    sample = summarise(
        f"POST /transactions (burst of {requests_count}, concurrency {concurrency})",
        timings,
        statuses,
        len(results) - len(timings),
    )
    return sample, throughput, accepted


def measure_end_to_end(accepted_keys: list[str]) -> dict:
    """How long the asynchronous half takes: accepted, to an assessment on disk.

    Measured from the database rather than from an endpoint, because the
    question is when the pipeline finished rather than when a reader could see
    it. Kafka, the outbox relay, the consumer and the scoring call are all
    inside this number, which is why it is reported as one and not broken down —
    breaking it down would need per-hop timing this system does not emit.
    """
    if not accepted_keys:
        return {"measured": False, "reason": "nothing was accepted"}

    quoted = ",".join("'" + key.replace("'", "''") + "'" for key in accepted_keys)
    started = time.perf_counter()
    deadline = started + ASSESSMENT_TIMEOUT_SECONDS
    total = len(accepted_keys)

    while time.perf_counter() < deadline:
        value = psql(
            "SELECT count(*) FROM risk_assessments r"
            f" JOIN transactions t ON t.id = r.transaction_id WHERE t.idempotency_key IN ({quoted})"
        )
        done = int(value.splitlines()[0]) if value and value.splitlines() else 0
        if done >= total:
            elapsed = time.perf_counter() - started
            # `degraded` and `risk_band`, which are the columns that exist.
            # There is no `outcome` column; an earlier version of this asked for
            # one, and because the failure was swallowed it reported an empty
            # section rather than an error.
            breakdown = psql(
                "SELECT r.degraded, r.risk_band, r.alert_raised, count(*) FROM risk_assessments r"
                f" JOIN transactions t ON t.id = r.transaction_id WHERE t.idempotency_key IN ({quoted})"
                " GROUP BY 1, 2, 3 ORDER BY 1, 2, 3"
            )
            outcomes = {}
            for line in breakdown.splitlines():
                parts = line.split("|")
                if len(parts) == 4:
                    degraded = "degraded" if parts[0] == "t" else "scored"
                    raised = "alert raised" if parts[2] == "t" else "no alert"
                    outcomes[f"{degraded}, {parts[1]}, {raised}"] = int(parts[3])
            return {
                "measured": True,
                "transactions": total,
                "secondsToAllAssessed": round(elapsed, 2),
                "outcomes": outcomes,
            }
        time.sleep(1.0)

    value = psql(
        "SELECT count(*) FROM risk_assessments r"
        f" JOIN transactions t ON t.id = r.transaction_id WHERE t.idempotency_key IN ({quoted})"
    )
    done = int(value.splitlines()[0]) if value and value.splitlines() else 0
    return {
        "measured": False,
        "reason": f"only {done} of {total} were assessed within {ASSESSMENT_TIMEOUT_SECONDS}s",
        "transactions": total,
        "assessed": done,
    }


def markdown(record: dict) -> str:
    env = record["referenceEnvironment"]
    out: list[str] = []
    add = out.append

    add("# SentinelFlow benchmark report")
    add("")
    add(
        "> Generated by `scripts/bench/benchmark.py`. Every number here is from the run recorded"
        " below, on the machine recorded below, against the dataset recorded below. **A latency"
        " figure without those three is not reproducible**, which is why they come first."
    )
    add("")
    add("## Reference environment")
    add("")
    add("| Field | Value |")
    add("| --- | --- |")
    add(f"| Measured at (UTC) | {env['measuredAtUtc']} |")
    add(f"| Commit | `{env['gitSha']}` |")
    add(f"| OS | {env['os']} ({env['machine']}) |")
    add(f"| Logical processors | {env['processors']} |")
    add(f"| Docker | {env['docker']} |")
    add(f"| Docker Compose | {env['dockerCompose']} |")
    add(f"| PostgreSQL | {env['postgres']} |")
    add("")
    add("**Dataset the queries ran against:**")
    add("")
    add("| Table | Rows |")
    add("| --- | --- |")
    for table, count in sorted(env["datasetRows"].items()):
        add(f"| `{table}` | {count:,} |")
    add("")
    add("**This is one machine, once.** It is a developer laptop running the whole stack — ten")
    add("containers, a database, a broker and two application services competing for the same")
    add("cores. Nothing here is a claim about what this design does on dedicated hardware, and")
    add("no figure below should be quoted without the row above it.")
    add("")

    add("## Read latency")
    add("")
    add(
        "Thirty requests per endpoint, paced a quarter of a second apart. **The pacing is"
        " deliberate**: the standard rate allowance is 300 a minute with a burst of 60"
        " (ADR-0017 §2), and a tight loop would spend the burst and start timing the limiter's"
        " refusals rather than the query behind them. Pacing does not change a latency"
        " distribution."
    )
    add("")
    add("| Endpoint | n | p50 ms | p95 ms | p99 ms | min ms | max ms |")
    add("| --- | --- | --- | --- | --- | --- | --- |")
    for sample in record["reads"]:
        add(
            f"| {sample['name']} | {sample['ok']} | {sample['p50']} | {sample['p95']}"
            f" | {sample['p99']} | {sample['minimum']} | {sample['maximum']} |"
        )
    add("")
    add(
        "Percentiles are nearest-rank, not interpolated: with thirty samples an interpolated"
        " p99 invents a value between two observations, and every number here is meant to be"
        " one that actually happened."
    )
    add("")

    ingest = record["ingestion"]
    throughput = record["throughput"]
    add("## Ingestion")
    add("")
    add(
        "One burst, because the burst is what the limiter permits at once. **The sustained"
        " ceiling is a configured policy** — 600 a minute (ADR-0017 §2) — rather than a"
        " property of the pipeline, so measuring through it would measure the policy. This"
        " benchmark does not raise the limit to flatter itself; it stays under the burst and"
        " says what one accepted transaction costs."
    )
    add("")
    add("| Field | Value |")
    add("| --- | --- |")
    add(f"| Requests | {ingest['count']} |")
    add(f"| Accepted | {ingest['ok']} |")
    add(f"| Concurrency | {throughput['concurrency']} |")
    add(f"| Wall clock | {throughput['wallClockSeconds']} s |")
    add(f"| Accepted per second | {throughput['acceptedPerSecond']} |")
    add(f"| Rate limited (429) | {throughput['rateLimited']} |")
    add(f"| p50 / p95 / p99 ms | {ingest['p50']} / {ingest['p95']} / {ingest['p99']} |")
    add(f"| min / max ms | {ingest['minimum']} / {ingest['maximum']} |")
    add("")
    add(
        "**Accepted per second is not a throughput ceiling.** It is what this burst achieved at"
        " this concurrency on this machine while the rest of the stack was running beside it."
    )
    add("")

    e2e = record["endToEnd"]
    add("## End to end: accepted to assessed")
    add("")
    add(
        "Measured from the database rather than from an endpoint, because the question is when"
        " the pipeline finished rather than when a reader could see it. Kafka, the outbox"
        " relay's poll interval, the consumer and the scoring call are all inside this one"
        " number — the system emits no per-hop timing, so breaking it down would mean inventing"
        " the split."
    )
    add("")
    if e2e.get("measured"):
        add("| Field | Value |")
        add("| --- | --- |")
        add(f"| Transactions | {e2e['transactions']} |")
        add(f"| Seconds until all were assessed | {e2e['secondsToAllAssessed']} |")
        for outcome, count in sorted(e2e.get("outcomes", {}).items()):
            add(f"| {outcome} | {count} |")
        if not e2e.get("outcomes"):
            add("| Outcome breakdown | **none returned — read the query, not the zero** |")
        add("")
        add(
            "**The outbox relay polls every 500 ms by default** (ADR-0005), so roughly half of"
            " that is in every one of these figures before anything else happens. Nothing in"
            " this pipeline is real-time and the design says so."
        )
    else:
        add(f"**Not measured.** {e2e.get('reason', 'unknown')}")
    add("")

    add("## What this benchmark does not measure")
    add("")
    add(
        "- **Sustained throughput.** The rate limiter's ceiling is the binding constraint by"
        " design, and this run stays under it rather than raising it."
    )
    add(
        "- **Anything under contention from a second client.** One driver, one machine, the"
        " stack running beside it."
    )
    add(
        "- **Cold starts.** The stack was already warm; a first request after a restart pays"
        " for connection setup and JIT that nothing here isolates."
    )
    add(
        "- **The console.** These are API measurements. What a browser renders adds a network"
        " hop, a bundle and a render this does not touch."
    )
    add(
        "- **Any hardware but the one named above.** Run it yourself; the command is"
        " `make bench`."
    )
    add("")
    return "\n".join(out)


def main() -> int:
    parser = argparse.ArgumentParser(description="Benchmark the running SentinelFlow stack.")
    parser.add_argument("--base", default=os.environ.get("SENTINELFLOW_API_BASE", DEFAULT_BASE))
    parser.add_argument("--api-key", default=os.environ.get("SENTINELFLOW_INGEST_API_KEY", ""))
    parser.add_argument("--username", default=os.environ.get("SENTINELFLOW_BENCH_USER", "analyst.one"))
    parser.add_argument(
        "--password", default=os.environ.get("SENTINELFLOW_DEMO_OPERATOR_PASSWORD", "")
    )
    parser.add_argument("--requests", type=int, default=INGEST_REQUESTS)
    parser.add_argument("--concurrency", type=int, default=INGEST_CONCURRENCY)
    parser.add_argument("--json-out", default="docs/performance/benchmark.json")
    parser.add_argument("--report-out", default="docs/performance/BENCHMARK.md")
    args = parser.parse_args()

    if not args.api_key:
        raise SystemExit(
            "SENTINELFLOW_INGEST_API_KEY is not set. Ingestion has required a credential since"
            " ADR-0017; `make bootstrap` generates one into .env."
        )
    if not args.password:
        raise SystemExit(
            "SENTINELFLOW_DEMO_OPERATOR_PASSWORD is not set. The read benchmarks sign in as a"
            " seeded operator; `make bootstrap` generates one into .env."
        )

    status, _, _ = request("GET", f"{args.base}/actuator/health/readiness")
    if status != 200:
        raise SystemExit(f"The API is not ready at {args.base} (readiness answered {status}).")

    print("Reading the reference environment")
    environment = reference_environment()

    account = reference(
        "SELECT account_reference FROM accounts ORDER BY account_reference LIMIT 1", "account"
    )
    merchant = reference(
        "SELECT merchant_reference FROM merchants ORDER BY merchant_reference LIMIT 1", "merchant"
    )

    print(f"Ingesting {args.requests} transactions at concurrency {args.concurrency}")
    ingestion, throughput, accepted = measure_ingestion(
        args.base, args.api_key, account, merchant, args.requests, args.concurrency
    )

    print("Waiting for the asynchronous half to finish")
    end_to_end = measure_end_to_end(accepted)

    print("Signing in for the read benchmarks")
    token = sign_in(args.base, args.username, args.password)

    print(f"Measuring read latency, {READ_ITERATIONS} requests per endpoint, paced")
    reads = measure_reads(args.base, token)

    record = {
        "referenceEnvironment": environment,
        "reads": [asdict(sample) for sample in reads],
        "ingestion": asdict(ingestion),
        "throughput": throughput,
        "endToEnd": end_to_end,
    }

    os.makedirs(os.path.dirname(args.json_out) or ".", exist_ok=True)
    with open(args.json_out, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(record, handle, indent=2, sort_keys=True)
        handle.write("\n")
    with open(args.report_out, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(markdown(record))

    print(f"\nWrote {args.report_out} and {args.json_out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
