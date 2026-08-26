# ADR-0005 — Outbox relay mechanics

- **Status:** Accepted
- **Date:** 2026-08-26
- **Related:** [ADR-0006](0006-event-schema-and-versioning.md),
  [ADR-0007](0007-money-identifiers-and-schema-migrations.md)

## Context

[ADR-0006](0006-event-schema-and-versioning.md) decided that publication goes through a
transactional outbox and that delivery is at-least-once with idempotent consumers. It did not decide
how the relay that drains the outbox behaves, and that is a separate set of choices with its own
failure modes.

The table already exists — `outbox_events`, created in
`V6__outbox_processed_events_and_audit.sql` and carrying `status`, `attempt_count`, `last_error`,
`next_attempt_at` and `partition_key`, with a partial index `outbox_events_due_idx` over
`(next_attempt_at, id) WHERE status = 'PENDING'`. So this ADR chooses a policy over columns that are
already there, and the schema constrains what it can decide: `outbox_events_published_at_consistent`
requires that `PUBLISHED` and a publication time agree in both directions.

Five questions have to be answered before the relay is written rather than after:

1. How does the relay learn there is work — polling, or logical decoding?
2. How does a batch get claimed, so two instances cannot publish the same row twice?
3. What is the retry schedule, and what bounds it?
4. When does an event stop being retried, and what happens to it then?
5. What does an operator do with a row that has stopped?

Each of these is visible in stored data or in operational behaviour, so getting one wrong is not a
refactor.

## Decision

### 1. Polling, not logical decoding

The relay polls `outbox_events` on a fixed interval, default **500 ms**, configurable through
`sentinelflow.outbox.poll-interval`.

Logical decoding — Debezium reading the WAL — is the lower-latency and lower-load option, and it is
the right answer at a volume this project will not reach. It costs a connector to run, a replication
slot to monitor (an unconsumed slot retains WAL until the disk fills, which is a production incident
with a well-known shape), and a second deployment artifact for a demo whose whole point is that the
outbox pattern is legible. Polling a partial index over a table whose `PENDING` population is
transient is cheap, and it keeps the mechanism visible in this repository rather than in a
connector's configuration.

**500 ms is a publication delay, and it is the honest cost of the outbox.** It is written down here
so nothing later describes this pipeline as real-time.

### 2. A batch is claimed with `FOR UPDATE SKIP LOCKED`

```sql
SELECT * FROM outbox_events
WHERE status = 'PENDING' AND next_attempt_at <= now()
ORDER BY next_attempt_at, id
LIMIT :batchSize
FOR UPDATE SKIP LOCKED
```

`FOR UPDATE` takes a row lock, so a second relay instance cannot select the same rows.
`SKIP LOCKED` makes it step over rows another instance already holds instead of blocking behind
them, which is what lets two instances make progress at the same time rather than serialising.

Without `SKIP LOCKED`, a second instance blocks until the first commits and then does nothing
useful; without `FOR UPDATE`, both instances publish the same event. The duplicate would be _handled_
— consumers deduplicate on `eventId` — but relying on the consumer's safety net to cover the
producer's race means the safety net is load-bearing for normal operation rather than for the
exception it was built for.

**Batch size defaults to 100**, configurable. Ordering is `(next_attempt_at, id)`, which is the
partial index's own order, so the scan is an index range read and the oldest due event goes first.

**The claim, the publish and the status update are one database transaction.** The row is marked
`PUBLISHED` only after the broker has acknowledged, so a relay that dies mid-batch rolls back and the
events are simply due again. That is the at-least-once side of the bargain being paid deliberately.

### 3. Retry is exponential with full jitter, and it is bounded

`next_attempt_at = now() + random(0, min(base × 2^attempt, ceiling))`

- **base** 1 s (`sentinelflow.outbox.retry.base`)
- **ceiling** 5 min (`sentinelflow.outbox.retry.max-delay`)
- **maximum attempts** 10 (`sentinelflow.outbox.retry.max-attempts`)

**Full jitter, not fixed and not plain exponential.** Without jitter, everything that failed during
a broker outage retries in lockstep the instant it recovers and knocks it over again — the outage
synchronises the retries, and the retries extend the outage. Full jitter spreads them across the
whole window rather than clustering them at its end.

**The row stays `PENDING` while it is still going to be retried.** `FAILED` is terminal. Marking a
retryable failure `FAILED` would take it out of `outbox_events_due_idx`, which is the only query the
relay makes, and the event would silently never be published again.

Ten attempts at this schedule is roughly 25 minutes of trying before giving up — long enough to
ride out a broker restart, short enough that a genuinely broken event is visible within an hour.

### 4. An exhausted event becomes `FAILED` and stays

After the tenth failed attempt the row moves to `FAILED` with `last_error` set. It is **not**
deleted, and it is **not** published to the dead-letter topic.

The DLQ ([ADR-0006](0006-event-schema-and-versioning.md) §4) is for messages a _consumer_ could not
process — it is reached through Kafka, and an event that never reached Kafka cannot be put there by
definition. A `FAILED` outbox row is a producer-side failure and belongs where it already is, next
to the aggregate it describes and inside the same database an operator is already looking at.

`last_error` holds an exception type and a sanitised message. Never a stack trace, never a payload
fragment, never anything from a secret: the column is `varchar(1000)` in a table an operator reads,
and [ADR-0006](0006-event-schema-and-versioning.md) applies the same rule to DLQ records for the
same reason.

### 5. Retrying a `FAILED` event is an authorized, audited operation

Not a script, not a `psql` session, and not automatic. It is an administrator-only endpoint that
resets `status` to `PENDING`, `attempt_count` to 0 and `next_attempt_at` to now, and writes an
`audit_log` entry attributing the action to the user who made it.

Automatic resurrection is deliberately absent. An event that failed ten times over 25 minutes failed
for a reason, and re-queueing it without anyone looking turns a visible problem into a loop.

### 6. What the relay must expose from the first commit

| Signal                                       | Why it is not deferrable                                           |
| -------------------------------------------- | ------------------------------------------------------------------ |
| `sentinelflow_outbox_pending`                | Depth. Rising monotonically means the relay is not keeping up      |
| `sentinelflow_outbox_oldest_age_seconds`     | Lag. The number that says how stale the event stream is            |
| `sentinelflow_outbox_failed`                 | Rows that gave up. Should be 0; anything else is an operator's job |
| `sentinelflow_outbox_publish_total{outcome}` | Success and failure counts, for a rate                             |
| `sentinelflow_outbox_publish_seconds`        | Publish latency                                                    |

Depth alone cannot distinguish a busy relay from a stuck one; age can. Both are needed, which is
why both are here rather than one being added after an incident.

**Each publication carries the correlation and trace identifiers already stored on the row**, so a
request, its outbox row, its Kafka record and its consumer's log lines share an identifier without
the relay having to reconstruct one.

## Alternatives considered

**Debezium and logical decoding.** Rejected for v1 on operational cost, not on merit: a connector to
run, a replication slot whose neglect fills a disk, and a second deployment artifact. It is the
right answer at a volume this project does not reach, and the mechanism would move out of this
repository into a connector's configuration, which is the opposite of what a demonstration of the
outbox pattern wants. **Revisit if** poll latency or database load ever appears in a measurement.

**`LISTEN`/`NOTIFY` to wake the relay instead of polling.** Rejected. It removes the 500 ms delay in
the common case but is not a delivery mechanism: notifications are not durable, are dropped when no
listener is connected, and are lost on reconnect — so the poll has to exist anyway as the thing that
actually guarantees delivery. That makes it an optimisation on top of the mechanism, not a
replacement for it, and it is not worth two code paths at this scale.

**Advisory locks or a leader election, so only one relay instance runs.** Rejected. `SKIP LOCKED`
gives horizontal scaling for free and has no failover gap; a leader election adds a coordination
mechanism and a window during which nothing is publishing.

**Deleting rows once published.** Rejected for now. The published history is useful for debugging
and for answering "was this event ever emitted", and `outbox_events_due_idx` is partial so published
rows cost nothing to keep on the relay's read path. Growth is real and a retention sweep is Phase 7
work — recorded as debt rather than solved early with a `DELETE` that throws away the evidence.

**Publishing a `FAILED` outbox row to the DLQ topic.** Rejected: it would require the broker the row
failed to reach. If the broker is the problem, the DLQ write fails too, and the failure has nowhere
to go.

**Marking a row `FAILED` on the first failure and retrying from there.** Rejected: `FAILED` rows are
outside the relay's only index, so the event would never be looked at again.

## Consequences

**Positive.** Publication survives a broker outage without losing an event or failing an API
request. Two relay instances can run without coordination. Backoff is bounded, so a broken event
cannot retry forever, and it is jittered, so a recovering broker is not immediately knocked over
again. A stuck pipeline is visible in two metrics before anyone reports it. Nothing gives up
silently: an exhausted event is a row an operator can see, and reviving it is attributable.

**Negative.** Publication is delayed by up to the poll interval, so this pipeline is not real-time
and must not be described as one. `outbox_events` grows without bound until Phase 7 adds retention.
`FOR UPDATE SKIP LOCKED` holds row locks for the duration of a publish, so a slow broker holds
database locks — bounded by the batch size and the producer timeout, both configurable, and worth
watching. The 25-minute give-up window is a judgement, not a measurement.

**Revisit if:** measured poll latency or database load makes logical decoding worth its operational
cost; the `FAILED` count is routinely non-zero, which would mean the retry policy is wrong rather
than that operators are slow; or `outbox_events` growth forces retention earlier than Phase 7.
