# Runbooks

What to do when part of SentinelFlow misbehaves. Each runbook states symptoms, impact, diagnostics,
a safe mitigation, how to validate the fix, and what it deliberately does not cover.

> SentinelFlow is an independent educational project running on synthetic data. Nothing here
> describes a real financial system, and no runbook makes a real financial decision.

**This file grows with the system, and only ever documents behaviour that exists.** A runbook for a
component that has not been built would be a guess, and a guess in a runbook is worse than a gap
because somebody follows it. Four runbooks are written below: three cover the event pipeline that
Phase 3 delivered, and the fourth covers scoring degradation, which became a thing that happens when
Phase 4's assessment workflow landed. The remainder — API unavailable, database connection
saturation, high error rate, slow report query, and model artifact load failure — arrive with the
components they describe, in Phases 5 and 7. Phase 7 also adds the Grafana dashboards these
runbooks currently substitute Prometheus queries for.

## Where the numbers come from

Every metric named here is registered in application code and exposed on
`/actuator/prometheus`. They are not aspirational:

| Metric                                      | Type    | Registered in              | Means                                              |
| ------------------------------------------- | ------- | -------------------------- | -------------------------------------------------- |
| `sentinelflow_outbox_pending`               | gauge   | `OutboxRelay`              | Events written and not yet published               |
| `sentinelflow_outbox_failed`                | gauge   | `OutboxRelay`              | Events that exhausted the relay's retry budget     |
| `sentinelflow_outbox_oldest_age_seconds`    | gauge   | `OutboxRelay`              | Age of the oldest unpublished event                |
| `sentinelflow_outbox_publish_total`         | counter | `OutboxBatchProcessor`     | Publication attempts, tagged `outcome`             |
| `sentinelflow_consumer_events_total`        | counter | `IdempotentEventProcessor` | Events handled, tagged `consumer` and `outcome`    |
| `sentinelflow_consumer_deadletter_total`    | counter | `DeadLetterRecoverer`      | Records dead-lettered, tagged `class`              |
| `sentinelflow_consumer_undeliverable_total` | counter | `DeadLetterRecoverer`      | Records that were not even readable as an envelope |
| `sentinelflow_risk_assessments_total`       | counter | `RiskAssessmentService`    | Assessments written, tagged `outcome` and `band`   |

**No threshold below is calibrated against a measured baseline**, because no load test has been run
— Phase 9 does that. They are starting points chosen from the configured intervals, and each says
which configured value it is derived from so it can be re-derived rather than guessed at again.

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
operation, and the endpoint that exposes it is Phase 5 work; until it exists, reprocessing means
republishing the `originalEvent` from the dead-letter record to its source topic, and that should be
a considered act with a record of who did it and why.

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

- Consumer lag is read from the broker rather than exported as a SentinelFlow metric. Phase 7 adds
  the exporter and the dashboard; until then the `kafka-consumer-groups.sh` command above is the
  source of truth.
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
  the DLQ this is an administrator-only, audited operation (ADR-0005 §5) whose endpoint is Phase 5
  work. Until it exists, reviving means resetting `status` to `PENDING`, `attempt_count` to 0 and
  `next_attempt_at` to now, for rows whose cause has actually been fixed — and recording who did it.

**Do not delete outbox rows to clear the backlog.** Each one is a state change that has already
happened and that nothing downstream has been told about.

### Validation

`sentinelflow_outbox_pending` falls and stabilises near zero.
`sentinelflow_outbox_oldest_age_seconds` returns to under the poll interval.
`sentinelflow_outbox_failed` is zero. Consumers see the events:
`sentinelflow_consumer_events_total` climbs to match.

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

### Known limitations

- **Assessments written while scoring was down stay degraded.** There is no rescoring endpoint yet;
  ADR-0005 §5 makes it an administrator-only audited operation and Phase 5 owns it.
- **The breaker's state is not exposed as a metric.** `ScoringClient.circuitState()` answers it in
  process and nothing publishes it, so "is the breaker open" is currently inferred from the absence
  of per-record warnings. Worth a gauge when Phase 7 adds the dashboards.
- **A degraded assessment and a scored one are banded by the same policy**, deliberately (ADR-0008
  §4). That is what makes them comparable; it also means a degraded assessment can band lower than
  the same transaction would have banded with the model, and nothing flags that difference.
