# ADR-0016 — Observability: what each signal answers, and what none of them may carry

- **Status:** Accepted
- **Date:** 2026-08-30
- **Related:** [ADR-0005](0005-outbox-relay-mechanics.md),
  [ADR-0006](0006-event-schema-and-versioning.md),
  [ADR-0008](0008-scoring-service-boundary.md),
  [ADR-0011](0011-risk-banding-and-the-final-score.md),
  [ADR-0015](0015-live-updates-polling-and-server-sent-events.md)

## Context

Three defects in this build reached a green suite and were found only by somebody watching the
running stack: no process created the Kafka topics, the scoring client negotiated HTTP/2 against an
HTTP/1.1-only service, and two PowerShell targets had never worked. The first two are the relevant
pair. Both were silent in exactly the same way — every container reported healthy, every test
passed, and the pipeline either did nothing or did the wrong thing at every single record.
Testcontainers builds the system the tests describe; `compose.yaml` builds the system a demo runs
on, and only the second one can be observed.

What exists before this decision:

1. **Eleven application metrics, all in the API.** Assessments by outcome and band, alerts raised by
   priority and band, alert transitions, consumer events by outcome, dead-letter counters, outbox
   publish counters and timer, and three outbox gauges. Actuator adds `http.server.requests`, the
   Hikari pool, and the JVM and process series for free.
2. **Three metrics in the scoring service**, on the default `prometheus_client` registry: requests
   by outcome, an inference-duration histogram with buckets chosen for a millisecond call, and a
   model-loaded gauge.
3. **Nothing measures the broker.** Consumer lag and dead-letter depth are properties of Kafka, and
   no process in this stack has ever asked it for either. `docs/operations/RUNBOOKS.md` says to read
   lag with `kafka-consumer-groups.sh` by hand.
4. **Nothing measures the scoring call from the caller's side.** `risk.assessed.v1` records
   `scoringLatencyMs`, and `ScoringClient` computes it per record and writes it into the payload —
   where it can be queried per assessment and aggregated by nothing.
5. **Logs are plain text on both sides.** The API runs Logback's default pattern encoder.
   `structlog` is a declared dependency of the scoring service and every log call goes through it,
   but nothing ever calls `structlog.configure`, so it renders with its console defaults.
6. **`CorrelationIdFilter` puts a UUID on every HTTP request** — in the MDC, on a response header,
   and onto the outbox row that becomes a Kafka record. Nothing carries it across the Kafka hop into
   the consumer's log context, and nothing links an API request to the scoring call it caused except
   that the client copies the header across.
7. **Prometheus 3.14 and Grafana 13.2 are in `compose.yaml`, health-gated, with a provisioned
   datasource and no dashboards.** `rule_files` is empty and says why. The OpenTelemetry Collector
   was deliberately left out: nothing exported traces, and a collector receiving nothing is
   decoration.
8. **Three console panels were deleted in Phase 6** because every number in them was invented. Their
   replacement text names throughput, scoring latency percentiles, consumer lag and dead-letter
   depth as Phase 7's, and says a figure nobody measured is worse than no figure.

The question this ADR settles is not "should there be observability" — the phase is named for it. It
is which signal answers which question, so the three do not become three copies of each other, and
what none of them may carry, because a metrics label and a log line are the two easiest places in
this system to leak something.

## Decision

### 1. Three signals, three questions, no overlap

| Signal      | Answers                                                  | Cardinality it may carry                      |
| ----------- | -------------------------------------------------------- | --------------------------------------------- |
| **Metrics** | Is the system healthy, and how much work is moving?      | Bounded, closed enumerations only             |
| **Traces**  | Where did **this one** transaction go, and for how long? | One identifier per request, sampled           |
| **Logs**    | What exactly happened, in words, at this moment?         | Whatever a human needs, minus what §4 forbids |

The rule that follows from the table: **no metric is ever added to answer a question about one
transaction.** That is what traces are for. Every time the answer to "can we label this by account?"
is yes, the cost is a series per account, retained for seven days, holding an identifier in a
label — three separate problems with one cause.

### 2. Metric naming, and the closed-label rule

Names are `sentinelflow.<area>.<noun>` in Micrometer's dot form, which the Prometheus registry
renames to `sentinelflow_<area>_<noun>_<unit>`. `<area>` is one of `transactions`, `risk`, `alerts`,
`outbox`, `consumer`, `kafka`, `scoring`.

**A label's values must come from a closed set fixed in code — an enum, a constant, a topic name —
and never from a request, a payload, a database row, or an exception message.** This is already how
the eleven existing metrics are built; it is written down here because it is the rule that is
easiest to break by accident and hardest to unpick afterwards. The following are forbidden as label
values, permanently: account, customer, merchant, transaction, alert and correlation identifiers;
amounts; device references; user names or operator identifiers; IP addresses; URL paths taken from
the request rather than from a route template; exception messages.

A new metric states its complete label domain in the comment where it is defined, together with the
number of series it can produce. If that number is not a small constant, it is the wrong metric.

### 3. Percentiles are computed by Prometheus, from histogram buckets

Micrometer will publish client-side percentiles (`publishPercentiles`) or bucket counts
(`publishPercentileHistogram`). This project publishes **buckets**, for both the scoring call and
`http.server.requests`, because a pre-computed percentile cannot be aggregated: the p99 of two
instances' p99 values is not a number about anything. The dashboards use `histogram_quantile` over
`rate(..._bucket[5m])`, which survives a second replica and a change of aggregation window.

Buckets are declared with an SLO-shaped range rather than left at Micrometer's defaults, for the
reason the scoring service already declares its own: a call budgeted at two seconds and expected in
milliseconds lands in the first default bucket every time, and a histogram whose observations all
fall in one bucket measures nothing.

### 4. Redaction is a property of what is logged, not a filter over it

Structured JSON logging in containers, human-readable text on a developer's terminal, on both
services. The decision underneath the format is the one that matters:

**A log line is built from named fields chosen at the call site. It never serialises a domain
object, a request body, an event payload, or an entity.** A deny-list regex over rendered output is
not a control — it fails open on the first field somebody adds, and its passing tests only prove it
matched the strings whoever wrote them thought of.

**What a log line may and may not carry is a list, not a judgement call.** Two categories, and the
split is not "identifier versus not" — it is what an investigation needs against what a disclosure
would cost:

| Allowed, and needed                                                                                                                                                                  | Forbidden at every level                                                                                                                                   |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Correlation id, trace and span id, transaction id, alert id and reference, account reference, merchant reference, event type, topic, partition, offset, outcome, risk band, duration | Monetary amounts, device references, idempotency keys, credentials, tokens and password hashes, whole request bodies, whole event payloads, whole entities |

An account reference is how an operator finds the thing they were paged about, and it already
appears in this API's own responses and problem documents; forbidding it in the log would trade a
real capability for no protection. An amount, a device handle and a caller-chosen idempotency key
are the parts worth withholding — the last one also because it is caller-controlled text, which is
the log-injection surface `CorrelationIdFilter` already refuses to reflect.

Two mechanisms enforce this, and they differ in kind:

- **`toString()` on the types that carry forbidden fields renders identifiers and types only.** So
  the failure mode where somebody logs the whole object degrades to something harmless rather than
  to a disclosure.
- **A redaction test asserts the negative against a real captured log stream**: drive the ingestion
  and scoring path with a transaction whose amount, device reference and idempotency key are
  distinctive values, capture every line the service emits at every level including `DEBUG`, and
  assert none of those values appears. It is a backstop for the rule above, not the implementation
  of it — and it runs with `DEBUG` enabled on purpose, because an assertion that holds only because
  a level is set to `INFO` is an assertion about configuration rather than about code.

Hibernate's `org.hibernate.orm.jdbc.error` logger is already pinned to `ERROR` for exactly this
reason, with the reason written into `application.yaml`. That one is the precedent, not the
exception.

### 5. Trace context is W3C, and it crosses Kafka as record headers

`traceparent` and `tracestate`, propagated on the HTTP hop from console to API and from API to
scoring, and across the Kafka hop as record headers written by the producer and read by the
listener. The correlation identifier stays: it is the operator-facing handle that appears in a
problem document and on an outbox row, and it is chosen by the caller where a trace id is chosen by
the tracer. Both appear on every log line. Neither replaces the other.

Export is OTLP to an **OpenTelemetry Collector**, which forwards to **Tempo**, which Grafana reads
through a provisioned datasource. The collector earns its place now that something exports to it,
and it is the seam that keeps the applications ignorant of where traces are stored.

**Sampling is 100% locally, and that is a statement about the traffic rather than a default nobody
thought about.** This stack produces transactions in bursts from `make seed` and `make replay` and
is otherwise idle; sampling one in ten of a four-record burst produces a trace view that is empty
when somebody looks at it. The rate is configuration, and it is the first number to change if this
ever runs anywhere continuous.

### 6. Consumer lag and dead-letter depth are measured by the API, with an admin client

They belong to the broker, and the alternative to the API asking for them is a `kafka-exporter`
container: another image, another service, and another thing to keep at a version. The API already
holds a configured connection to the broker and already publishes a Prometheus endpoint, so it
asks — on a schedule, off the request path, with a timeout, and failing to a stale value rather than
to an exception.

Two series, both with bounded labels:

- `sentinelflow.kafka.consumer.lag`, tagged by consumer group and topic, **summed across
  partitions**. Per-partition would be `groups × topics × partitions` series to answer a question
  nobody asks first; the runbook that follows a climbing number reaches for
  `kafka-consumer-groups.sh` for the per-partition breakdown.
- `sentinelflow.kafka.dlq.depth`, tagged by topic — the number of records in the dead-letter topic
  within its retention window, which is the end offset minus the start offset on its single
  partition.

**That second one is a depth, not a backlog of unhandled failures.** Nothing consumes the
dead-letter topic, so the number does not fall when somebody deals with a record; it falls when the
30-day retention expires. That is stated on the metric, on the dashboard panel and in the runbook,
because a gauge whose meaning has to be guessed is exactly the kind of number somebody quotes.

### 7. Grafana owns the time series; the console does not grow a metrics page

The three panels Phase 6 emptied are not refilled with charts drawn by the console. Throughput,
latency percentiles and lag over time belong to Grafana, and the console links to the dashboard
rather than growing a query layer against Prometheus, a second set of time-series components, and a
second place where the same number can be computed two ways.

**One exception, and it is the health screen.** Consumer lag and dead-letter depth are now measured
inside the API, which means the health endpoint can report the current reading as a fact about the
pipeline rather than as a chart. That is a number an operator wants on the screen that answers "is
this thing working", it is a single reading rather than a series, and it redeems the promise the
placeholder text made. It goes on `GET /platform/health` as an additive contract change.

## Consequences

- **Two new containers**, the collector and Tempo, and neither has a health-gated dependant: a
  tracing backend that is down must not stop the pipeline. Exporters fail quietly by design.
- **The API takes a Kafka `AdminClient` dependency it did not have**, plus one scheduled task that
  talks to the broker. It is bounded by a timeout and degrades to a stale gauge.
- **A log line's field set becomes an interface.** Changing which fields a line carries changes what
  a saved query or a dashboard can find. That is the cost of structure, and it is worth paying.
- **The redaction test is slow, and it is not optional.** It drives a real path and captures real
  output; a fast version asserting over a hand-built string would prove nothing.
- **`scoringLatencyMs` now exists in two places** — on every `risk.assessed.v1` payload and in a
  histogram. They are the same measurement at two granularities, deliberately: the payload answers
  "why is this assessment degraded", the histogram answers "is scoring slow today".
