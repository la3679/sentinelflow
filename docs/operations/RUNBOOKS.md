# Runbooks

What to do when part of SentinelFlow misbehaves. Each runbook states symptoms, impact, diagnostics,
a safe mitigation, how to validate the fix, and what it deliberately does not cover.

> SentinelFlow is an independent educational project running on synthetic data. Nothing here
> describes a real financial system, and no runbook makes a real financial decision.

**This file grows with the system, and only ever documents behaviour that exists.** A runbook for a
component that has not been built would be a guess, and a guess in a runbook is worse than a gap
because somebody follows it. **Ten runbooks are written below.** Nine are the set
`docs/planning/IMPLEMENTATION_PLAN.md` asks Phase 7 for: dead-letter growth, consumer lag, outbox
backlog and scoring degradation cover the pipeline Phases 3 and 4 built; the API being down,
connection saturation, a high server error rate, a slow report query and a model that will not load
cover the rest of what this stack can do wrong. The tenth arrived with the control that made it
possible — Phase 8's rate limiting adds a way for the stack to refuse a caller on purpose, and a
refusal nobody can diagnose is a defect wearing a status code.

**Every runbook names metrics that exist and dashboard panels that are provisioned.** Five
dashboards live in `infra/grafana/dashboards/`; each runbook says which one to open first. The four
that predate the dashboards have been revised against them, and where an earlier limitation has
since been fixed the sentence is struck through and dated rather than deleted — a runbook whose
history is visible is one a reader can trust about the present.

**Two of these failures are exercised by tests rather than described.**
`apps/api/src/test/java/…/resilience/` holds a drill for each: `ScoringOutageDrillIT` fails the
scoring service under load and asserts what Runbook 4 promises, and `BrokerOutageDrillIT` freezes
the broker mid-run and asserts what Runbook 3 promises. Both run in `make test-integration`. What
they establish is written into those two runbooks under "What a drill actually showed", so the
behaviour described there is observed rather than reasoned about.

**Alerting rules for these conditions are in `infra/prometheus/rules/sentinelflow.yml`**, each one
annotated with the runbook it points at. There is no Alertmanager in this stack, so nothing pages
anybody: a firing rule appears on Prometheus's own Alerts page at <http://localhost:9090/alerts>.
The rules are worth having anyway, because they write the thresholds down where they can be read
and argued with.

## Where the numbers come from

Every metric named in this file is registered in application code and exposed on the API's
`/actuator/prometheus` or the scoring service's `/metrics`. None is aspirational, and all of them
were queried against the running stack when the dashboards landed.

**Application metrics, registered by SentinelFlow:**

| Metric                                            | Type      | Registered in                 | Means                                                       |
| ------------------------------------------------- | --------- | ----------------------------- | ----------------------------------------------------------- |
| `sentinelflow_transactions_ingested_total`        | counter   | `TransactionIngestionService` | Ingestion attempts, tagged `source` and `outcome`           |
| `sentinelflow_outbox_pending`                     | gauge     | `OutboxRelay`                 | Events written and not yet published                        |
| `sentinelflow_outbox_failed`                      | gauge     | `OutboxRelay`                 | Events that exhausted the relay's retry budget              |
| `sentinelflow_outbox_oldest_age_seconds`          | gauge     | `OutboxRelay`                 | Age of the oldest unpublished event                         |
| `sentinelflow_outbox_publish_total`               | counter   | `OutboxBatchProcessor`        | Publication attempts, tagged `outcome`                      |
| `sentinelflow_outbox_publish_duration_seconds`    | histogram | `OutboxBatchProcessor`        | Time one publication attempt cost                           |
| `sentinelflow_consumer_events_total`              | counter   | `IdempotentEventProcessor`    | Events handled, tagged `consumer` and `outcome`             |
| `sentinelflow_consumer_deadletter_total`          | counter   | `DeadLetterRecoverer`         | Records dead-lettered, tagged `consumer` and `class`        |
| `sentinelflow_consumer_undeliverable_total`       | counter   | `DeadLetterRecoverer`         | Records that were not even readable as an envelope          |
| `sentinelflow_kafka_consumer_lag`                 | gauge     | `KafkaPipelineMetrics`        | Records the group has not read, summed across partitions    |
| `sentinelflow_kafka_dlq_depth`                    | gauge     | `KafkaPipelineMetrics`        | Records on the dead-letter topic, a depth and not a backlog |
| `sentinelflow_scoring_calls_total`                | counter   | `ScoringClient`               | Scoring calls made, tagged `outcome`                        |
| `sentinelflow_scoring_call_duration_seconds`      | histogram | `ScoringClient`               | Caller-side scoring latency, including retries              |
| `sentinelflow_scoring_breaker_state`              | gauge     | `ScoringClientConfiguration`  | 1 on the state the breaker is in, tagged `state`            |
| `sentinelflow_risk_assessments_total`             | counter   | `RiskAssessmentService`       | Assessments written, tagged `outcome` and `band`            |
| `sentinelflow_alerts_raised_total`                | counter   | `AlertRaiser`                 | Alerts opened, tagged `priority` and `band`                 |
| `sentinelflow_alerts_transitions_total`           | counter   | `AlertService`                | Queue transitions, tagged `from` and `to`                   |
| `sentinelflow_alerts_assignments_total`           | counter   | `AlertService`                | Assignment changes, tagged `outcome`                        |
| `sentinelflow_scoring_requests_total`             | counter   | scoring service               | Scoring requests as the service saw them, tagged `outcome`  |
| `sentinelflow_scoring_inference_duration_seconds` | histogram | scoring service               | Feature extraction and inference, service-side              |
| `sentinelflow_scoring_model_loaded`               | gauge     | scoring service               | 1 when a model is loaded and usable, 0 otherwise            |

**Framework metrics, which the runbooks below lean on just as heavily:**

| Metric                         | From              | Means                                                 |
| ------------------------------ | ----------------- | ----------------------------------------------------- |
| `http_server_requests_seconds` | Spring Boot       | Request count and latency, tagged `uri` and `status`  |
| `hikaricp_connections_active`  | HikariCP          | Connections checked out right now                     |
| `hikaricp_connections_idle`    | HikariCP          | Connections free in the pool                          |
| `hikaricp_connections_pending` | HikariCP          | **Threads waiting for one.** Above zero is the signal |
| `hikaricp_connections_max`     | HikariCP          | The ceiling, `DB_POOL_MAX_SIZE`, 10 by default        |
| `jvm_memory_used_bytes`        | Micrometer        | Heap and non-heap use, tagged `area`                  |
| `up`                           | Prometheus itself | 1 when the last scrape of a job succeeded             |

**Which dashboard to open first**, all provisioned from `infra/grafana/dashboards/` and reachable at
<http://localhost:3000>:

| Dashboard                       | Answers                                                              |
| ------------------------------- | -------------------------------------------------------------------- |
| SentinelFlow — platform         | Is the pipeline working at all? Four stat tiles and four graphs      |
| SentinelFlow — API and database | Request rate, latency percentiles, server errors, pool, heap         |
| SentinelFlow — Kafka and outbox | Outbox depth and age, publication, consumer outcomes, lag, DLQ depth |
| SentinelFlow — scoring          | Model loaded, breaker, degraded share, both sides' latency           |
| SentinelFlow — alerts and risk  | Alerts raised, bands, priorities, queue transitions                  |

**No threshold in this file or in `infra/prometheus/rules/sentinelflow.yml` is calibrated against a
measured baseline**, because no load test has been run — Phase 9 does that. Most are derived from a
configured interval or budget and say which one; the two that are conventions rather than
derivations say so where they appear.

---

## Runbook 1 — Dead-letter queue growth

### Symptoms

`sentinelflow_consumer_deadletter_total` increasing. Records present on
`transaction.processing.dlq.v1`. Transactions sitting at `processing_status = 'FAILED'` in the
console with no assessment.

### Impact

**Every dead-lettered event is a transaction that will never be assessed** unless the record is
reprocessed. This is not a degradation that resolves itself: the consumer has committed the offset
and moved on, deliberately, so that one bad record does not stall the partition behind it.

Severity depends entirely on the `failureClass`, which is why it is a field rather than something to
infer:

| `failureClass`             | What it means                                    | Usually                                   |
| -------------------------- | ------------------------------------------------ | ----------------------------------------- |
| `RETRY_EXHAUSTED`          | Transient failure that did not clear in ~5 tries | A dependency is down — see Runbook 2      |
| `SCHEMA_VALIDATION_FAILED` | The event does not match the contract it claims  | A producer deployed ahead of a consumer   |
| `UNKNOWN_EVENT_TYPE`       | Nothing dispatches this `eventType`              | A new event type without its consumer     |
| `MALFORMED_PAYLOAD`        | Not deserialisable at all                        | Something is producing to the wrong topic |
| `NON_RETRYABLE_ERROR`      | A handler said this cannot ever succeed          | Reference data missing, or a real defect  |

A rising `RETRY_EXHAUSTED` count is an outage. A rising `SCHEMA_VALIDATION_FAILED` count is a
release problem. They are not the same incident and must not be triaged the same way.

**`RETRY_EXHAUSTED` on _every_ record, with the same `exceptionType` on each, is not a dependency
being down.** It is a permanent condition wearing a transient class, because a handler that throws a
plain exception is retried before anyone asks whether retrying could ever help. On 2026-08-29 that
was one missing reference row — the `system` principal, absent from a database whose `users` table
had been truncated — and every `transaction.created` event was retried five times and dead-lettered
while ingestion kept answering `202` and system health kept reporting every component operational.
The API now refuses to start against a database missing that row, so this exact case cannot recur;
the shape of it can. Read `sanitisedMessage` on a dead-lettered record before assuming an outage.

### Diagnostics

Break the count down before doing anything else. The class is the diagnosis:

```promql
sum by (class) (increase(sentinelflow_consumer_deadletter_total[15m]))
```

Read the records themselves. They carry the original envelope unmodified, the source coordinates,
the exception type, and a sanitised message:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic transaction.processing.dlq.v1 --from-beginning --max-messages 20
```

Find the transactions that are affected:

```sql
SELECT id, transaction_reference, occurred_at
  FROM transactions
 WHERE processing_status = 'FAILED'
 ORDER BY occurred_at DESC
 LIMIT 50;
```

Check the application log for the corresponding line. Each dead-letter is logged at `ERROR` with the
event id, the topic, the partition, the offset and the attempt count.

### Mitigation

**Fix the cause before reprocessing anything.** A `NON_RETRYABLE_ERROR` reprocessed without a change
fails identically and produces a second dead-letter record.

- `RETRY_EXHAUSTED` — restore the dependency (Runbook 2), then reprocess.
- `SCHEMA_VALIDATION_FAILED` or `UNKNOWN_EVENT_TYPE` — deploy the consumer that understands the
  event. Under ADR-0006 a breaking change publishes to a new topic, so if this is happening on
  `.v1` a producer has broken the compatibility policy and that is the defect to fix.
- `MALFORMED_PAYLOAD` — find what is producing to the topic. Nothing in this repository can produce
  an unreadable envelope through the outbox, so the producer is either external or misconfigured.

Reprocessing is deliberately not a script. ADR-0005 §5 makes it an administrator-only, audited
operation.

**No endpoint exposes it, and no phase currently owns building one.** An earlier version of this
runbook said Phase 5 did; Phase 5 shipped, its deliverable list never contained it, and nothing in
`docs/planning/IMPLEMENTATION_PLAN.md` allocates it now. Corrected here rather than left pointing at
a phase that has already closed. Until an endpoint exists, reprocessing means republishing the
`originalEvent` from the dead-letter record to its source topic, and that should be a considered act
with a record of who did it and why.

**Do not delete records from the dead-letter topic to make the number go down.** The number is the
only remaining evidence of what failed.

### Validation

`sentinelflow_consumer_deadletter_total` stops increasing.
`sentinelflow_consumer_events_total{outcome="processed"}` resumes. The affected transactions leave
`processing_status = 'FAILED'` once reprocessing succeeds — the consumer's ledger makes a second
delivery of an already-handled event a no-op, so reprocessing a partially-successful batch is safe.

### Known limitations

- A record that was not readable as an envelope at all is **not** on the dead-letter topic: the
  schema requires a valid envelope and ADR-0006 §4 forbids copying unsanitised content onto an
  operational topic. It is counted under `sentinelflow_consumer_undeliverable_total` and logged with
  its exact topic, partition and offset, and the original bytes remain readable there for as long as
  retention holds them. Read them from the source topic by offset.
- `attemptCount` and `firstFailedAt` are measured by the process that did the retrying. A restart or
  a rebalance mid-retry resets them, so they undercount in that case rather than overcounting.

---

## Runbook 2 — Consumer lag and a stalled partition

### Symptoms

Consumer group lag rising on `transaction.created.v1`. Transactions accepted by the API and staying
`PENDING`. `sentinelflow_consumer_events_total` flat while
`sentinelflow_outbox_publish_total{outcome="success"}` keeps climbing — events are being published
and not consumed.

### Impact

Assessment falls behind ingestion. Nothing is lost: offsets are committed per record after the
listener returns, so a consumer that is behind is late rather than lossy. But an analyst is looking
at a console that is missing recent activity, and that is worth knowing quickly.

### Diagnostics

Read the group's lag from the broker:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group transaction-risk
```

Lag concentrated on **one partition** while others are current is the signature of a blocking retry:
the consumer retries in-process, so a record failing repeatedly holds everything behind it in that
partition. That is a deliberate trade — ADR-0006 §2 keys transaction events by account so an
account's events stay ordered, and a non-blocking retry topic would break exactly that ordering.

The retry budget bounds how long one record can block: `sentinelflow.consumer.max-attempts` (5)
deliveries with `retry-base` 500 ms doubling to a `retry-max-delay` ceiling of 20 s, so roughly half
a minute, after which the record is dead-lettered and the partition moves on. **A partition blocked
for materially longer than that is not a retry** — look for a handler that is hanging rather than
failing.

Lag spread evenly across partitions is a throughput problem, not a stall. Check whether the handler
is waiting on something: in Phase 3 the only handlers are whatever is registered against
`TransactionCreatedHandler`, and from Phase 4 that is the scoring service.

### Mitigation

- **Stalled on one partition** — let the retry budget expire. It is bounded on purpose, and the
  record will dead-letter within seconds. Then work Runbook 1.
- **Handler hanging rather than failing** — this is the case the retry budget does not bound, because
  a call that never returns never fails. Restore or restart the dependency it is waiting on. If the
  dependency is unreachable, the consumer can be switched off with
  `SENTINELFLOW_CONSUMER_ENABLED=false` and restarted; events accumulate on the topic under its
  retention and are consumed when it is switched back on.
- **Throughput** — the topic's partition count bounds parallelism. Adding partitions changes how keys
  map to partitions and therefore breaks per-account ordering for events already published; do it
  deliberately, not during an incident.

### Validation

Lag returns to near zero and stays there. `sentinelflow_consumer_events_total{outcome="processed"}`
resumes climbing. Transactions leave `PENDING`.

### Known limitations

- ~~Consumer lag is read from the broker rather than exported as a SentinelFlow metric.~~
  **Superseded 2026-08-30.** `sentinelflow_kafka_consumer_lag`, summed across partitions, is
  exported by the API and graphed on the platform and Kafka dashboards. The
  `kafka-consumer-groups.sh` command above is still the way to get the **per-partition** breakdown,
  which the metric deliberately does not carry (ADR-0016 §6).
- `outcome="duplicate"` climbing alongside `processed` is **not** a fault. At-least-once delivery
  means duplicates are ordinary traffic, and the ledger absorbing them is the design working.

---

## Runbook 3 — Outbox backlog

### Symptoms

`sentinelflow_outbox_pending` rising monotonically.
`sentinelflow_outbox_oldest_age_seconds` growing past a few seconds.
`sentinelflow_outbox_publish_total{outcome="failure"}` increasing.
`sentinelflow_outbox_failed` above zero.

### Impact

State changes are committed and not published. **Nothing is lost** — that is what the outbox is for,
and the row survives a restart — but every downstream consumer is working from a stale view, and the
backlog is unbounded until the broker returns.

`sentinelflow_outbox_failed` above zero is a different and worse signal: those events exhausted the
relay's budget of ten attempts over roughly 25 minutes and **will not be retried automatically**.
ADR-0005 §4 makes `FAILED` terminal deliberately, because an event that failed ten times over 25
minutes failed for a reason, and re-queueing it without anyone looking turns a visible problem into
a loop.

### Diagnostics

Depth alone cannot distinguish a busy relay from a stuck one — a queue of constant size is healthy if
it is turning over. Read depth and age together:

```promql
sentinelflow_outbox_pending
sentinelflow_outbox_oldest_age_seconds
rate(sentinelflow_outbox_publish_total{outcome="failure"}[5m])
```

The relay polls every `sentinelflow.outbox.poll-interval` (500 ms by default), so an oldest-age above
a couple of seconds means drains are failing or not happening at all, not that the interval is slow.

What is actually failing is recorded on the rows:

```sql
SELECT status, count(*), min(occurred_at) AS oldest, max(last_error) AS example
  FROM outbox_events
 WHERE status <> 'PUBLISHED'
 GROUP BY status;
```

`last_error` holds an exception type and a sanitised message — never a stack trace, by design. Check
the application log for `Outbox drain failed` (the relay's own poll threw, usually the database) and
for `gave up after` (an individual event exhausted its attempts).

### Mitigation

- **Broker unreachable** — restore it. The relay recovers on its own: rows stay `PENDING`, the
  backoff is jittered so the whole backlog does not stampede the broker the instant it returns, and
  the queue drains without intervention. Do nothing else.
- **Relay not running** — confirm `SENTINELFLOW_OUTBOX_ENABLED` is not `false` and that the
  application has `@EnableScheduling` active. A relay that is switched off publishes nothing and
  errors nowhere, which is the failure mode hardest to notice.
- **`FAILED` rows present** — read `last_error`, fix the cause, then revive them deliberately. As with
  the DLQ this is an administrator-only, audited operation (ADR-0005 §5) and **no endpoint exposes
  it**; the note that used to say Phase 5 owned that endpoint was wrong, and Runbook 1's mitigation
  records the correction once for both. Reviving means resetting `status` to `PENDING`,
  `attempt_count` to 0 and `next_attempt_at` to now, for rows whose cause has actually been fixed —
  and recording who did it.

**Do not delete outbox rows to clear the backlog.** Each one is a state change that has already
happened and that nothing downstream has been told about.

### Validation

`sentinelflow_outbox_pending` falls and stabilises near zero.
`sentinelflow_outbox_oldest_age_seconds` returns to under the poll interval.
`sentinelflow_outbox_failed` is zero. Consumers see the events:
`sentinelflow_consumer_events_total` climbs to match.

### What a drill actually showed

`BrokerOutageDrillIT` freezes the broker mid-run and asserts every clause of the paragraph above, so
none of it is reasoning. With the broker frozen: ingestion kept answering `202`, because the write
path ends at a table; the events sat `PENDING` with `attempt_count` climbing and `last_error`
recorded on the row; `sentinelflow_outbox_pending` reported the backlog and
`sentinelflow_outbox_oldest_age_seconds` was non-zero beside it; and nothing reached `FAILED`. When
the broker came back, the relay's next poll found the same rows still due and drained them with no
intervention, leaving exactly one `processed_events` row and one assessment per event.

The drill compresses the producer's delivery timeout, the relay's poll interval and its backoff, and
raises `max-attempts` to keep the shipped meaning of the retry budget; the class comment says why for
each. **It pauses the broker rather than stopping it**, because Docker re-picks the published host
port on a start and the broker that came back would be at an address nothing was configured for. So
what it proves is survival of a broker that stops answering — a stalled disk, a one-sided partition —
rather than of one that refuses connections. Both reach the same place in this system: the producer's
delivery timeout expires and `KafkaEventPublisher` throws.

### Known limitations

- A publication delay of at least one poll interval is inherent to the design, not a fault. ADR-0005
  §1 records it: nothing in this pipeline is real-time.
- An event published to the broker whose transaction then failed to commit is republished, so a
  duplicate here is expected. Consumers deduplicate on `eventId`; that is the other half of the
  at-least-once bargain and not a defect to chase.

---

## Runbook 4 — Assessments are degrading

### Symptoms

- `sentinelflow_risk_assessments_total{outcome="degraded"}` climbing while
  `{outcome="scored"}` is flat.
- The API logs `Scoring did not answer in 3 attempts; the assessment will degrade to rules`, once
  per record while the breaker is closed and then not at all once it opens.
- Newly written rows in `risk_assessments` have `degraded = true`, a null `model_score` and a null
  `model_version`.

### Impact

**Nothing is lost and nothing is rejected.** A degraded assessment is a real answer from the
transparent rule baseline, which runs in the API's own process precisely so it can answer when the
scoring service cannot (ADR-0008 §3). Ingestion is unaffected: a transaction is accepted, committed
and published before any of this runs.

What is lost is the model's contribution. The rule score is a floor, so a degraded assessment can
only be **lower** than the one the model would have produced — the shapes the rules do not encode go
unseen while this lasts. Treat it as reduced coverage, not as an outage.

A degraded assessment is **not** upgraded automatically when scoring recovers. Rescoring is a
deliberate, audited operation (ADR-0008 §2), because a background job silently changing decisions an
analyst may already have acted on is worse than a decision that is honest about what produced it.

### Diagnostics

Is the scoring service up and serving a model?

```bash
docker compose ps scoring
curl -fsS http://localhost:8000/health/ready
curl -fsS http://localhost:8000/v1/model
```

Readiness answers 503 when no model is loaded, which is a different fault from a container that is
down: the service is running and cannot score. `/v1/model` names the model version and its artifact
checksum.

Is the breaker open? The API stops attempting calls entirely once it is, which is the intended
behaviour and is why the per-record warning goes quiet:

```bash
curl -fsS http://localhost:8080/actuator/prometheus | grep sentinelflow_risk_assessments
```

Is the service answering but **refusing**? That is a different fault with a different response:

```bash
docker compose logs scoring | grep 'request rejected'
docker compose logs api | grep 'Scoring rejected the request'
```

A refusal is never degraded. It dead-letters the record, so it shows up in Runbook 1's symptoms
instead — and it means the two services disagree about
`contracts/openapi/sentinelflow-scoring.yaml`, which is a defect to fix rather than a condition to
wait out. **Check the scoring service's log for `Unsupported upgrade request` beside each
rejection**: that pairing means the caller is negotiating a protocol uvicorn does not serve, and it
is what caused every request to be refused the first time this pipeline ran against the real stack.

### Mitigation

If the container is down or unhealthy:

```bash
docker compose up -d --wait scoring
```

If it is up but not ready, the model artifact is the thing to look at — the image carries a registry
entry under `apps/scoring/models/<name>/<version>/`, and loading validates the artifact checksum, the
feature version and the recorded column order before serving.

**Then wait.** The circuit breaker stays open for its configured window
(`sentinelflow.scoring.client.circuit-breaker-open-duration`, 30 s by default) after the service is
healthy again, and the first call after that is a half-open probe. Transactions posted inside that
window still degrade, which looks like the restart not having worked and is not.

Do not raise the client's timeouts to "give scoring more time". The whole budget is validated at
startup against ADR-0008 §3's ten-second ceiling, and it is small because a consumer's retry blocks
its partition: everything queued behind a record waits for it.

### Validation

`sentinelflow_risk_assessments_total{outcome="scored"}` starts climbing again, and rows written
after the recovery have a `model_score` and a `model_version`.

`make replay` exercises exactly this, deliberately: it stops the scoring container, shows four
degraded assessments, restarts it, waits out the breaker, and shows four scored ones.

### What a drill actually showed

`ScoringOutageDrillIT` posts thirty transactions through the whole path while scoring refuses, and
asserts what this runbook promises rather than describing it. Every one of the thirty was assessed;
every assessment was marked degraded and carried **no** model score, rather than a zero; none was
dead-lettered and every transaction reached `ASSESSED`.

The number worth carrying into an incident is the last one. The breaker keeps its shipped threshold
of five in that drill, and the thirty transactions cost **five records' worth of HTTP attempts, not
thirty** — the remaining twenty-five degraded without a call being attempted, counted by
`sentinelflow_scoring_calls_total{outcome="breaker_open"}`. That is what stops a scoring outage from
becoming consumer lag proportional to traffic, and it is why the per-record warning going quiet is
the breaker working rather than the problem clearing.

Recovery in the drill needed nothing restarted and nothing reset: the open window elapsed, the next
transaction was let through as the probe, it succeeded, and the pipeline scored again. **Transactions
consumed while the breaker was still open stayed degraded for ever**, which is correct and is the
thing most likely to look like a failed restart.

### Known limitations

- **Assessments written while scoring was down stay degraded.** There is no rescoring endpoint, and
  no phase currently owns building one — the same correction Runbook 1 records. ADR-0005 §5 fixes
  what it would have to be when it exists: administrator-only and audited. `RiskAssessmentService`
  writes every assessment at version 1 and nothing rescores, so today the only honest statement is
  that a degraded assessment stays degraded.
- ~~**The breaker's state is not exposed as a metric.**~~ **Superseded 2026-08-30.**
  `sentinelflow_scoring_breaker_state` publishes one series per state, each 0 or 1, so
  `sentinelflow_scoring_breaker_state{state="OPEN"} == 1` answers "is the breaker open" directly. It
  is on the platform and scoring dashboards. Caller-side scoring latency and calls by outcome are
  exported too, so the inference-from-absent-warnings step below is no longer necessary.
- **A degraded assessment and a scored one are banded by the same policy**, deliberately (ADR-0008
  §4). That is what makes them comparable; it also means a degraded assessment can band lower than
  the same transaction would have banded with the model, and nothing flags that difference.

---

## Runbook 5 — The API is down

### Symptoms

`up{job="sentinelflow-api"} == 0` in Prometheus. Every panel on the API dashboard flat and empty
rather than reading zero. `docker compose ps api` showing the container restarting, unhealthy, or
gone. The console showing its error state on every screen, and
`curl http://localhost:8080/actuator/health` refusing the connection.

### Impact

**Total, and immediate.** Ingestion, the console, the reports and every actuator endpoint are
unavailable. The relay and the consumer live in this process too, so events stop being published and
stop being consumed for as long as it lasts.

**Nothing is lost.** A transaction that was accepted was committed with its outbox row in one
transaction before the response was written, and both survive the restart. Events already published
sit on the topic under its seven-day retention and are consumed from the group's committed offset
when the consumer returns. What is lost is availability, which is the whole of it.

### Diagnostics

Separate the three cases before doing anything, because they need different responses: the process is
not running, the process is running and not ready, or the process is fine and something in front of
it is not.

```bash
docker compose ps api
docker compose logs --tail=200 api
curl -fsS http://localhost:8080/actuator/health/liveness
curl -fsS http://localhost:8080/actuator/health/readiness
```

Liveness and readiness are separate on purpose. **A process that is up and cannot reach PostgreSQL is
live and not ready**, and the image's `HEALTHCHECK` probes readiness rather than liveness so that an
orchestrator routes traffic only when the service can serve it.

**If the container is restarting, the cause is almost always a refusal to start rather than a crash
under load.** This service refuses to start on several conditions deliberately, and each one names
its own fix in the log:

| The log says                                           | What is wrong                                               |
| ------------------------------------------------------ | ----------------------------------------------------------- |
| Could not resolve placeholder `POSTGRES_PASSWORD`      | The variable is unset. It has no default, deliberately      |
| Flyway validation failed, or a checksum mismatch       | The database is not one these migrations produced unaltered |
| A schema-validation error on an entity                 | Mappings and schema have drifted; `ddl-auto` is `validate`  |
| The `system` principal is missing from the users table | `ReferenceDataVerifier`. See below; this one has history    |
| The scoring call budget exceeds the ceiling            | `ScoringClientProperties` refuses a budget over ten seconds |
| A blank JWT signing key                                | `JwtProperties` refuses one (ADR-0012 §6)                   |
| Band lower bounds with a gap or an inversion           | `RiskPolicyProperties` refuses to clamp a band table        |

**The `system` principal refusal is a feature rather than an obstacle.** On 2026-08-29 that row was
absent from the local demo database, and the symptom was not a refusal to start — it was a service
that came up healthy, accepted transactions, reported every component operational, and dead-lettered
every event at the last step. The check exists so that failure is loud. Do not work around it by
inserting a row by hand; recreate the database with `make reset-demo`, which re-runs the migrations
that insert it.

If the process is up and ready and the outside world still cannot reach it, the port mapping is what
is left:

```bash
docker compose port api 8080
curl -fsS http://localhost:8080/actuator/health
```

### Mitigation

```bash
docker compose up -d --wait api
```

`--wait` returns when the container is healthy rather than when it has started, which is the
difference between "Docker accepted the command" and "the service is serving".

If the refusal is a configuration one, fix the configuration rather than the check. Every refusal in
the table above exists because the alternative — starting and failing later — was harder to
diagnose, and most of them were added after something was.

**Do not disable the health check to make the container report healthy.** It probes readiness, and a
container that reports healthy while it is not ready is one an orchestrator sends traffic to.

### Validation

`up{job="sentinelflow-api"} == 1`. `/actuator/health/readiness` answers `UP`. The platform
dashboard's ingestion and assessment tiles come back. `sentinelflow_outbox_pending` falls as the
relay drains what accumulated and `sentinelflow_kafka_consumer_lag` falls as the consumer catches up
— both spike after any outage of this kind and neither is a second incident.

### Known limitations

- **This stack has one API instance**, so this runbook is about restarting a process rather than
  failing over. There is no load balancer and no second replica; ADR-0002 keeps the deployment a
  single compose stack on one machine.
- **`show-details` on the health endpoint is `when-authorized`**, so an unauthenticated caller sees
  `UP` or `DOWN` and not which component is down. That is deliberate — component names are internal
  detail — and it means the container log, not the health endpoint, is where the diagnosis is.
- **A refusal to start is invisible in the metrics.** The process never gets far enough to serve
  `/actuator/prometheus`, so the only signals are `up == 0` and the container log. That is why the
  `ApiDown` rule fires on `up` rather than on anything the application publishes.

---

## Runbook 6 — Database connection saturation

### Symptoms

`hikaricp_connections_pending` above zero and staying there. `hikaricp_connections_active` pinned at
`hikaricp_connections_max`. Latency rising on **every** endpoint at once rather than on one. Requests
failing after about ten seconds with an internal error rather than eventually succeeding.

### Impact

Every request that needs the database queues for a connection, and one that waits longer than
`spring.datasource.hikari.connection-timeout` — 10 s — fails. The pool is shared, so one endpoint
holding connections degrades all of them, including the relay and the consumer, which draw from it
too.

The pool is small on purpose: `DB_POOL_MAX_SIZE` is 10. That is not a number to raise during an
incident — see the mitigation.

### Diagnostics

Read the four pool series together. Active against max says whether the pool is full; pending says
whether anything is waiting for it:

```promql
hikaricp_connections_active
hikaricp_connections_idle
hikaricp_connections_pending
hikaricp_connections_max
```

The "Connection pool" panel on the **API and database** dashboard plots all four on one axis, which
is the shape worth looking at: active at max with pending above zero is saturation; active at max
with pending at zero is a pool that is exactly busy enough.

Then find what is holding them. The pool is named `sentinelflow-api-pool` precisely so that a
connection holding a lock is attributable rather than being one of ten identical rows:

```sql
SELECT pid, state, wait_event_type, wait_event,
       now() - query_start AS running_for,
       left(query, 120) AS query
  FROM pg_stat_activity
 WHERE application_name LIKE 'sentinelflow%'
   AND state <> 'idle'
 ORDER BY running_for DESC;
```

Three shapes, and they are different incidents:

- **One long query with everything else waiting** — a report over a wide window is the usual one.
  Runbook 8.
- **Many connections `idle in transaction`** — a transaction opened and not closed. `open-in-view` is
  `false` here, so a request cannot hold one open across view rendering; look instead for a
  `@Transactional` method calling something slow.
- **Every connection genuinely working** — this is throughput, and the pool is the bound.

### Mitigation

- **A long query** — cancel it deliberately and narrow whatever asked for it.
  `SELECT pg_cancel_backend(<pid>)` cancels the statement; `pg_terminate_backend` drops the whole
  connection and is the second choice, not the first.
- **Idle in transaction** — restarting the API frees them, and the code path that leaked one is the
  actual fix.
- **Genuine throughput** — reduce concurrency at the source before enlarging the pool.

**Raising `DB_POOL_MAX_SIZE` during an incident is usually the wrong move.** A pool larger than the
database can serve moves the queue out of the application, where it is visible and bounded by
`connection-timeout`, and into PostgreSQL, where it is neither. The thing to change first is whatever
is holding connections for seconds at a time.

### Validation

`hikaricp_connections_pending` returns to zero and stays there. `hikaricp_connections_active` drops
below max between requests. Latency percentiles return to their previous shape across every route
rather than only the one that was slow.

### Known limitations

- **No number here is measured.** `DB_POOL_MAX_SIZE` is 10 because that is a sensible default for a
  laptop stack, not because a benchmark said so. Phase 9 measures ingestion and query throughput, and
  this is the first value that measurement should revisit.
- **`max-lifetime` is 25 minutes**, below PostgreSQL's `idle_session_timeout` and any sensible proxy
  timeout, so the pool discards a connection before the server does. A connection being replaced on
  that cadence is the setting working, not a fault.
- **The pool is shared with the relay and the consumer.** Saturation caused by request traffic also
  shows up as an outbox backlog and as consumer lag; those are symptoms of this rather than separate
  incidents.

---

## Runbook 7 — A high server error rate

### Symptoms

The "Server errors" panel on the **API and database** dashboard above zero.
`sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))` climbing. Callers receiving
`application/problem+json` bodies titled `Internal error`, each carrying a `correlationId`.

### Impact

A 5xx is this service's own fault by definition. Everything the API refuses deliberately — a
malformed body, an unknown reference, a reused idempotency key, a forbidden transition, a version
conflict, an oversized export — is a 4xx with a specific problem type, and none of it is counted
here. A rising 5xx rate is therefore always a defect or a dependency, never a client behaving badly.

Ingestion is the case worth separating: a 5xx on `POST /api/v1/transactions` means the transaction
was **not** accepted and the caller has to retry. Its idempotency key is what makes that safe.

### Diagnostics

Find out where before finding out why:

```promql
sum by (uri, status) (rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
```

`uri` is the templated route rather than the requested path, so `/api/v1/alerts/{alertId}` is one
series and not one per alert. Concentration on a single route is a defect in that route; errors
spread across every route are a dependency — the database (Runbook 6), or the whole process if the
relay and the consumer are unhappy too.

Then read the log. **There is exactly one place an unhandled exception is recorded**, and it carries
the correlation identifier the caller was given:

```bash
docker compose logs api | grep 'Unhandled exception serving'
```

The body the caller received holds no stack trace, no SQL and no internal class name, by design — so
the `correlationId` in their response and the one on that log line are the only way to join the two.
Ask for it. The container's logs are JSON, so it can be matched exactly rather than grepped for
loosely, and the trace identifier on the same line opens the request in Tempo.

### Mitigation

There is no generic mitigation, because a 5xx is a defect. What this runbook can offer is what to
rule out, in order:

1. **The database.** Runbook 6. A pool that cannot hand out a connection surfaces as a 500 on every
   route at once.
2. **Not scoring.** A scoring failure never produces a 5xx here — it degrades, and that is Runbook 4.
   A scoring _rejection_ dead-letters, which is Runbook 1.
3. **A migration that ran with a code path that has not caught up.** Hibernate validates mappings at
   startup so this is narrow, but a native query is not covered by that validation.
4. **The defect itself.** The log line names the exception and the route, the correlation identifier
   ties it to the request that provoked it, and the trace shows what it was doing.

**Do not add a catch that turns the 500 into a 200.** The handler already sanitises what the caller
sees; the status code is the honest part of it.

### Validation

The server-error rate returns to zero and the affected route serves normally.
`sentinelflow_transactions_ingested_total{outcome="created"}` resumes if ingestion was the route
affected.

### Known limitations

- **The 5% threshold on the `HighServerErrorRate` rule is a convention**, not a measurement. It fires
  on a share rather than a count so that one failure on an idle stack does not, and it is the first
  threshold Phase 9's baseline should replace.
- **A 4xx never appears here even when it should worry somebody.** A sudden rise in `409` on the
  alert transition endpoint means analysts are colliding, and a rise in scoring rejections means two
  services disagree about a contract. Both are worth watching; neither is a server error.
- **`http_server_requests_seconds` counts what reached a handler.** A request rejected before routing,
  or refused at CORS preflight, is not in it. ADR-0013 covers the cross-origin path.

---

## Runbook 8 — A slow report query

### Symptoms

`GET /api/v1/reports/alert-summary` or `GET /api/v1/reports/alerts.csv` taking seconds. The "Slowest
routes, p95" panel on the **API and database** dashboard showing a report route at the top. Often
`hikaricp_connections_pending` above zero at the same time, because a report holds its connection for
its whole duration.

### Impact

An analyst waits. If the report is slow enough and the pool is busy enough, everybody waits — which
is how one wide window becomes Runbook 6.

Nothing is at risk. Both endpoints are read-only and both are bounded: the summary is an aggregate
whose size cannot grow with the data — six statuses, four priorities, four bands — and the export is
capped at 10,000 rows and refuses with a `422` naming the row count and the limit rather than
streaming the table. The window is bounded to 366 days, and a request without one is refused rather
than defaulted to everything.

### Diagnostics

Which route, and how slow:

```promql
histogram_quantile(
  0.95,
  sum by (le, uri) (rate(http_server_requests_seconds_bucket{uri=~"/api/v1/reports.*"}[10m]))
)
```

Then ask how much data the window actually covers, which is nearly always the answer:

```sql
SELECT count(*) FROM alerts WHERE created_at >= :from AND created_at < :to;
```

And look at the plan, because there is a known shape here:

```sql
EXPLAIN ANALYZE
SELECT * FROM alerts
 WHERE created_at >= :from AND created_at < :to
 ORDER BY created_at, id;
```

**There is no index on `alerts (created_at)` alone.** The three that exist are
`alerts_queue_idx (status, priority, created_at DESC)`, `alerts_assignee_open_idx` — partial, over
assigned open alerts — and `alerts_transaction_idx`. None has `created_at` as its leading column, so
a report window is a sequential scan over `alerts` followed by a sort. At the volumes this demo
produces that is fast and invisible; over a large window on a large table it is the whole cost.

That is recorded here rather than fixed on the spot, deliberately. Phase 9 is where a measured
optimisation belongs, and an index added during an incident with no before-and-after is a change
nobody can justify afterwards.

### Mitigation

- **Narrow the window.** It is the fix in almost every case, and an oversized export's `422` already
  says how much to narrow it by, because it reports both the row count and the limit.
- **Prefer the summary to the export** when the question is "how many". The summary is an aggregate
  and does not grow with the data.
- **If it is blocking other traffic**, cancel it — Runbook 6's `pg_cancel_backend`, on the pid that
  `pg_stat_activity` attributes to `sentinelflow-api-pool`.

**Do not raise the export's row cap to make a request succeed.** The cap is what stops the endpoint
becoming a way to pull the whole table into memory in one request.

### Validation

The report route's p95 returns to where it was. `hikaricp_connections_pending` returns to zero. The
export either succeeds inside the cap or refuses with a `422` that names the numbers.

### Known limitations

- **Five seconds is a convention.** The `SlowReportQuery` rule fires above a p95 of five seconds
  because that is where a person would already call the request slow, not because anything was
  measured. Phase 9 replaces it.
- **The counts in a summary are counted, never estimated**, and that is a deliberate cost: nothing is
  sampled and nothing is cached, because an operations figure that is quietly approximate is one
  somebody will quote.
- **Nothing here covers the transaction list**, which is paged with a server-enforced maximum page
  size and is a different shape of query. A slow one there is Runbook 6, not this.

---

## Runbook 9 — The scoring model will not load

### Symptoms

Two quite different pictures, and telling them apart is the first step:

- **The service is up and serving nothing.** `sentinelflow_scoring_model_loaded == 0`,
  `GET /health/ready` answers `503`, `POST /v1/score` refuses with a retryable `503`, the API degrades
  every assessment, and the container log carries
  `no model in the registry; scoring will refuse with 503 and the API degrades to rules`.
- **The process will not start at all.** The container restarts in a loop,
  `up{job="sentinelflow-scoring"} == 0`, and the log ends in a `RegistryError`.

The "Model loaded" panel on the **scoring** dashboard separates them: 0 in the first case, no data at
all in the second.

### Impact

Identical downstream in both cases, and it is Runbook 4's: every assessment degrades to the rule
baseline, nothing is rejected, nothing is lost, and the model's contribution is missing from
everything written while it lasts. Assessments written during it stay degraded afterwards.

### Diagnostics

**A missing model and a broken one are deliberately different outcomes.** An empty registry is a
state the contract has a response for, so the service runs and reports `modelLoaded: false`. A
malformed entry raises and the process exits, because a checksum mismatch means a corrupted or
substituted artifact and serving around that quietly has no defensible reading.

```bash
docker compose ps scoring
docker compose logs --tail=100 scoring
curl -fsS http://localhost:8000/health/ready
curl -fsS http://localhost:8000/v1/model
```

Everything checkable about a registry entry is checked at startup, and each check fails with its own
message:

| The log says                                           | What is wrong                                                         |
| ------------------------------------------------------ | --------------------------------------------------------------------- |
| `no model in the registry`                             | Nothing under the models root matched this build's feature version    |
| pinned by configuration and is not in the registry     | The configured model name and version name an entry that is not there |
| An artifact SHA-256 mismatch                           | The artifact is corrupted or was substituted                          |
| A feature version mismatch                             | The entry was trained against a different feature build               |
| A column order mismatch                                | The manifest's columns are not this build's, in this order            |
| `metrics.json` reports one model, the manifest another | The figures beside the artifact belong to a different model           |
| No holdout metrics for the selected model              | `/v1/model` would have nothing to publish                             |

A pin that cannot be resolved is refused rather than falling back to discovery, deliberately: a
silent fallback serves a model nobody asked for.

What is actually in the image:

```bash
docker compose exec scoring ls -R /app/models
```

### Mitigation

- **An empty registry** is what a checkout with no trained model looks like, and it is a valid state
  rather than an incident. Train and register one; `apps/scoring` owns that command and
  `docs/ml/EVALUATION.md` records what a published model has to report. Never publish on accuracy
  alone — the classes are extremely imbalanced and accuracy is close to meaningless on them.
- **A failed pin** — either unset the pin so discovery runs, or add the entry that was pinned. Do not
  quietly repoint the pin at whatever happens to be present.
- **A checksum mismatch** — treat it as an integrity failure rather than a nuisance, and replace the
  entry from a known-good source. Do not regenerate the checksum from the artifact you already have;
  that turns the check into a formality.
- **A feature version or column order mismatch** — the entry was trained against a different build,
  so retrain against this one. Re-ordering a manifest's columns to make it load would serve a model
  whose inputs are silently permuted, which produces confident scores that mean nothing.

Whichever it was, the API recovers on its own once scoring is serving again — after the breaker's
open window, which is Runbook 4's last step.

### Validation

`sentinelflow_scoring_model_loaded == 1`. `/health/ready` answers `200`. `/v1/model` names the model
version, the feature version, the artifact checksum and the holdout metrics.
`sentinelflow_risk_assessments_total{outcome="scored"}` starts climbing, and rows written after the
recovery carry a `model_score` and a `model_version`.

### Known limitations

- **A model is resolved once, at startup**, so replacing one is a restart and there is no reload
  endpoint. That is deliberate: a model discovered on the first request would make the first request
  pay for it and would let a corrupted artifact sit undetected until traffic arrived.
- **`sentinelflow_scoring_model_loaded` does not recover on its own**, so the `ScoringModelMissing`
  rule waits ten minutes only to avoid firing across a restart, not because the condition is
  transient.
- **The registry is committed to this repository**, so "the model is missing" usually means a build
  or a mount rather than a lost file. `docker compose exec scoring ls -R /app/models` settles which.

## Runbook 10 — Everything is suddenly answering 429

### Symptoms

Callers receiving `429 Too Many Requests` with a `Retry-After` header and a problem body typed
`https://sentinelflow.example/problems/rate-limited`. The console showing errors on screens that
worked a minute ago, or `make replay` failing partway through a phase.

### Impact

Depends entirely on which allowance was reached, and the three are independent (ADR-0017 §2):

| Category  | Applies to                       | Default                   | What it looks like                                                 |
| --------- | -------------------------------- | ------------------------- | ------------------------------------------------------------------ |
| `login`   | `POST /api/v1/auth/login`        | 10 per minute, burst 10   | Nobody can sign in; everybody already signed in is unaffected      |
| `ingest`  | `POST /api/v1/transactions`      | 600 per minute, burst 120 | Transactions are refused. **Nothing is lost** — the caller retries |
| `default` | Everything else under `/api/v1/` | 300 per minute, burst 60  | The console degrades; the pipeline behind it keeps running         |

A 429 is not a failure of the request. Nothing was written, nothing was partially applied, and the
idempotency key on a refused ingestion is still unused — so a retry after the interval is safe and
is the intended response.

### Diagnostics

**First, decide whether this is the limiter working.** A demo reaching the `ingest` allowance is a
demo posting more than ten transactions a second, which `make replay` does not do. A single browser
reaching the `default` allowance is not a browser.

Which category, from the path in the `instance` field of the problem body:

```bash
curl -si -X POST "http://localhost:8080/api/v1/auth/login" \
  -H 'Content-Type: application/json' -d '{"username":"x","password":"y"}' | head -20
```

Read the configured values rather than assuming the defaults:

```bash
docker compose exec api env | grep SENTINELFLOW_RATE_
```

An empty result means every category is at its default, which is the normal case.

**The one non-obvious cause: everybody shares a bucket.** The limiter keys on a hash of
`X-API-Key` when there is one, and on the remote address otherwise. Every browser reaching the API
through the same address is therefore one caller as far as the `default` allowance is concerned.
On a laptop that is one person. Behind a NAT or a proxy it is everybody, and the limiter cannot
tell — no forwarding header is trusted, deliberately, because a forged one would let a single
caller spread itself across as many buckets as it liked.

### Resolution

- **If it is the limiter working**, do nothing to the limiter. Find what is making the requests.
- **If a legitimate workload needs more headroom**, raise that one category rather than all three,
  in `.env`, and restart the API:

  ```bash
  SENTINELFLOW_RATE_INGEST_PERMITS=2000
  SENTINELFLOW_RATE_INGEST_BURST=400
  ```

- **If the API was restarted**, every allowance is already full again: the buckets are in memory
  and a restart forgets them. That is worth knowing before somebody restarts it hoping to clear a
  limit and concludes the restart fixed something.

### Validation

A refused caller succeeds after waiting the interval the `Retry-After` header named. No `429` in
`sum by (uri, status) (rate(http_server_requests_seconds_count{status="429"}[5m]))`.

### Known limitations

- **The limit is per API instance.** Two instances behind a load balancer would permit twice the
  configured rate. Stated in ADR-0017 §2 rather than discovered here; the limiter belongs at an
  edge this stack does not have.
- **The refusal says nothing about the limit or what remains of it.** That is deliberate — a
  caller being limited needs to know to come back later, not how close it got — so the only way to
  read the configured values is the `docker compose exec` above.
- **There is no metric for refusals yet.** `http_server_requests_seconds_count{status="429"}` is
  what exists, and it comes from the actuator rather than from the limiter, so it counts refusals
  without saying which category or which caller.
