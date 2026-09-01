# Observability

What SentinelFlow emits, where it goes, and what each signal is allowed to be read as saying. The
decisions behind these boundaries are in
[ADR-0016](../adr/0016-observability-signals-and-their-boundaries.md).

## What starts with the stack

`make up` brings up Prometheus, Grafana, an OpenTelemetry Collector and Tempo alongside the
applications. Nothing needs clicking afterwards: the Prometheus datasource, the Tempo datasource
and all five dashboards are provisioned from files in
[`infra/grafana/provisioning/`](../../infra/grafana/provisioning/).

| Endpoint           | URL                                         |
| ------------------ | ------------------------------------------- |
| API metrics        | <http://localhost:8080/actuator/prometheus> |
| Scoring metrics    | <http://localhost:8000/metrics>             |
| Prometheus         | <http://localhost:9090>                     |
| Prometheus targets | <http://localhost:9090/targets>             |
| Prometheus alerts  | <http://localhost:9090/alerts>              |
| Grafana            | <http://localhost:3000>                     |
| Tempo              | <http://localhost:3200>                     |

## Dashboards

Five, provisioned from [`infra/grafana/dashboards/`](../../infra/grafana/dashboards/): platform,
API and database, Kafka and outbox, scoring, and alerts and risk.

**Every panel carries a description saying what the number means and what it does not.** The
dead-letter panel, for instance, says it is a depth rather than a backlog: nothing consumes that
topic, so the figure falls when retention expires rather than when somebody fixes something. A
panel without that sentence is a number waiting to be misread.

Every panel query was run against the live Prometheus before the dashboards were committed — 48
queries, none empty, none malformed.

**An absent series and a zero look identical on a graph and completely different in a rule.**
Writing the dead-letter alerting rule found `sentinelflow_consumer_deadletter_total` and
`sentinelflow_consumer_undeliverable_total` with no series at all on the running stack — the third
time that shape has been found here, after the dashboards found five. Every series a closed label
set can produce is now registered at zero in its constructor.

## Alerting rules

**Thirteen rules** live in
[`infra/prometheus/rules/sentinelflow.yml`](../../infra/prometheus/rules/sentinelflow.yml), each
annotated with the section of [`RUNBOOKS.md`](RUNBOOKS.md) that answers it. They deliberately waited
for the runbooks rather than landing beside the dashboards, because a rule with no runbook is a
pager nobody knows how to answer.

**No threshold is calibrated against a measured baseline.** Most are derived from a configured
budget or interval and name it; the two that are conventions say so.

**There is no Alertmanager in this stack**, so nothing pages anybody — a firing rule appears on
Prometheus's own Alerts page. Adding Alertmanager would be a service with no recipient. The rules
are worth having because they put the thresholds somewhere they can be read and argued with.

## Runbooks

**Ten**, in [`RUNBOOKS.md`](RUNBOOKS.md): dead-letter growth, consumer lag, outbox backlog, scoring
degradation, the API being down, connection saturation, a high server error rate, a slow report
query, a model that will not load, and a caller being rate limited. Each names real metrics, real
dashboard panels, and commands that were run rather than commands that ought to work.

## Tracing

W3C trace context crosses every hop, **including the asynchronous one**. The outbox stores the
originating `traceparent` and the relay replays it onto the Kafka record, so the consumer continues
the trace rather than starting a second one that nothing joins to the first
([ADR-0016 §5](../adr/0016-observability-signals-and-their-boundaries.md)).

One transaction can therefore be followed as a single trace from the HTTP request through the
outbox and Kafka to the scoring call.

- Tempo answers `GET /api/traces/<traceId>` on <http://localhost:3200>
- Traces are also reachable through Grafana's provisioned Tempo datasource

The API exports OTLP to an OpenTelemetry Collector, which forwards to Tempo. **Neither container
gates anything.** A tracing backend that is down must not stop the pipeline, so no application
depends on either and the exporter fails quietly. Export is off outside `compose`, so a local
`./mvnw spring-boot:run` still puts trace ids on its log lines without retrying a collector nobody
started.

## Logs

Structured, and redacted at the source rather than at the sink. `LogRedactionIT` runs at
`logging.level.root=DEBUG` over five paths and asserts that no amount, device handle, outbox
payload, analyst note, bearer token or password reaches a log line. What that test found when it
was widened is recorded in [`docs/testing/TEST_RESULTS.md`](../testing/TEST_RESULTS.md).

## What is deliberately not instrumented

Transaction throughput per hour, scoring latency percentiles, consumer-group lag and dead-letter
depth were once four charts and four tiles on the console, and nothing had measured any of them.
They were removed rather than filled in. **A figure nobody measured is worse than no figure**,
because somebody quotes it. Measured performance is reported in
[`docs/performance/BENCHMARK.md`](../performance/BENCHMARK.md) with the machine, the runtime and the
dataset that produced it.

`/actuator/prometheus` is currently unauthenticated, which is tracked as T-04 in the
[threat model](../security/THREAT_MODEL.md). A scrape cannot hold a token that expires every thirty
minutes; the real fix is a management port that is not published to the host.
