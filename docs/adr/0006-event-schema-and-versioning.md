# ADR-0006 — Event schema, topics, versioning, and delivery semantics

- **Status:** Accepted
- **Date:** 2026-08-25
- **Related:** [ADR-0002](0002-monorepo-and-service-boundaries.md),
  [ADR-0007](0007-money-identifiers-and-schema-migrations.md)

## Context

Phase 3 makes SentinelFlow event-driven. Before any producer or consumer exists, four things have
to be settled, because every one of them is baked into stored data and into every service that
touches an event:

1. What an event looks like on the wire.
2. What topics exist, how they are named, and what keys them.
3. How a schema is allowed to change once something is consuming it.
4. What delivery guarantees consumers may assume, and what happens when processing fails.

Getting these wrong is not a refactor. A topic name is in every consumer's configuration; an
envelope field is in every message already written; a partition key determines what ordering
guarantees exist and cannot be changed without repartitioning.

## Decision

### 1. Every event carries the same envelope

```json
{
  "eventId": "01936b2a-7c4f-7000-8000-2f9c1d4e5a6b",
  "eventType": "transaction.created",
  "schemaVersion": 1,
  "occurredAt": "2026-08-25T21:14:07.123456Z",
  "producer": "sentinelflow-api",
  "correlationId": "01936b2a-7c4f-7000-8000-1a2b3c4d5e6f",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "aggregateType": "transaction",
  "aggregateId": "01936b2a-7c4f-7000-8000-9f8e7d6c5b4a",
  "payload": {}
}
```

| Field           | Why it is mandatory                                                              |
| --------------- | -------------------------------------------------------------------------------- |
| `eventId`       | UUIDv7. The idempotency key consumers deduplicate on — see §4                    |
| `eventType`     | Routing and dispatch without parsing the payload                                 |
| `schemaVersion` | Integer. A consumer must be able to tell what it is holding before it reads it   |
| `occurredAt`    | When the fact happened, not when it was published (ADR-0007)                     |
| `producer`      | Which service emitted it, for operations and for provenance                      |
| `correlationId` | Ties every event, log line and span from one originating request together        |
| `traceId`       | W3C trace context when present; `null` rather than absent when it is not         |
| `aggregateType` | With `aggregateId`, identifies what the event is about without payload knowledge |
| `aggregateId`   | The entity's UUIDv7                                                              |
| `payload`       | The event-specific body. Everything above is transport; this is the message      |

**The envelope is versioned as a whole, separately from payloads.** `schemaVersion` describes the
payload contract for that `eventType`. A change to the envelope itself would be a v2 envelope and
a coordinated migration — which is exactly why it carries nothing that varies by event.

**Monetary values inside a payload are decimal strings, timestamps are ISO 8601 UTC, identifiers
are UUIDv7** — ADR-0007 applies inside events exactly as it does at the API boundary.

**No secrets, credentials, tokens, or personal data in any payload, ever**, including in DLQ
failure metadata. Events are long-lived, replayable, and readable by anything with topic access,
which makes them the worst possible place to put something sensitive.

### 2. Topics are named for business events, and there are five

| Topic                           | Key              | Produced by | Purpose                              |
| ------------------------------- | ---------------- | ----------- | ------------------------------------ |
| `transaction.created.v1`        | **account UUID** | api         | A transaction was accepted           |
| `risk.assessed.v1`              | **account UUID** | api         | A transaction was scored             |
| `alert.created.v1`              | **alert UUID**   | api         | An assessment opened an alert        |
| `alert.updated.v1`              | **alert UUID**   | api         | An alert changed state or assignment |
| `transaction.processing.dlq.v1` | original key     | api         | Terminal processing failures         |

**Named for business events, not for classes.** A topic per entity type produces a schema that
tracks the code and breaks whenever the code is refactored.

**The `.v1` suffix is in the topic name.** When a breaking change is unavoidable, `.v2` runs
alongside `.v1` and consumers migrate on their own schedule. This is the only breaking-change
mechanism that does not require every consumer to deploy simultaneously.

**Topics are created explicitly**, never by broker auto-creation, which is disabled in
`compose.yaml`. Auto-created topics get default partition counts and default retention, which are
never the values the design intended.

#### Partition keys, and the ordering they buy

Transaction and risk events are keyed by **account UUID**, not by transaction UUID.

Kafka guarantees ordering only within a partition. Velocity rules — "five transactions on this
account in one minute" — need the events for one account to arrive in order. Keying by transaction
UUID would spread one account's activity across every partition and destroy exactly the ordering
that matters. Keying by account gives per-account ordering, which is the strongest guarantee the
domain actually needs.

**There is no global ordering across accounts, and nothing may assume one.**

Alert events are keyed by alert UUID: an alert's state transitions must be ordered with respect to
each other, and have no ordering relationship with any other alert.

**Accepted consequence: a hot account is a hot partition.** With synthetic data this does not
arise; the alternative — a composite key — would buy throughput at the cost of the ordering
guarantee the rules depend on. Recorded so the trade-off is visible if it ever bites.

### 3. Compatibility is backward-compatible-by-default

A change to an existing `eventType` at the same `schemaVersion` **must** be backward compatible:

**Allowed** — adding an optional field with a default; adding a value to an enum _only where
consumers are documented to tolerate unknown values_; relaxing a constraint; documentation.

**Not allowed at the same version** — removing a field; renaming a field; changing a field's type;
making an optional field required; tightening a constraint; changing what a field means while
keeping its name. The last is the dangerous one, because no schema check catches it.

**Consumers ignore unknown fields.** Jackson is configured with
`FAIL_ON_UNKNOWN_PROPERTIES = false` for event deserialisation, and Pydantic models for events use
`extra="ignore"` — deliberately unlike the _configuration_ models, which use `extra="forbid"`
(ADR-0004). Configuration should reject a typo loudly; an event consumer should tolerate a
producer that has moved ahead of it.

**Breaking changes get a new `schemaVersion` and a new topic.** The old one keeps running until
its consumers have migrated, and its retirement is a deliberate act with a recorded date.

**Schemas are validated in producer _and_ consumer tests**, against the JSON Schema files in
`contracts/schemas/`. The contract is a file that both sides test against, not a shared class that
happens to compile in both — a shared class silently couples the two and makes an incompatible
change look safe.

`contracts/` is authoritative. Changing a contract means updating its producers, consumers, tests,
and documentation in the same change.

### 4. Delivery is at-least-once, and consumers are idempotent

**Publication uses a transactional outbox.** The state change and the outbox row are written in one
database transaction; a relay reads unpublished rows and publishes them. Writing the row and then
publishing to Kafka is two operations with a window between them, and a crash in that window loses
the event with no trace.

Kafka transactions were considered and rejected: they would couple the database transaction to
broker availability, so a broker outage would fail the API request rather than degrade publication.

**Consumers deduplicate on `eventId`** through a `processed_events` table with a composite
uniqueness constraint on (consumer name, event id). At-least-once means a duplicate is normal
traffic, not an incident. Deduplication is in the database rather than in memory because it must
survive a restart.

**Ordering assumption, stated so nothing relies on more:** per-partition, therefore per-account for
transaction and risk events and per-alert for alert events. Nothing else.

#### Failure classification

| Class             | Examples                                                              | Handling                                               |
| ----------------- | --------------------------------------------------------------------- | ------------------------------------------------------ |
| **Retryable**     | Scoring service unreachable or 5xx; database deadlock; broker timeout | Bounded retry with exponential backoff and full jitter |
| **Non-retryable** | Schema validation failure; unknown `eventType`; malformed payload     | Straight to DLQ, no retry                              |

Retrying a malformed message is pure waste — it will fail identically every time while blocking
the partition behind it.

**Backoff uses full jitter**, not fixed or purely exponential delay. Without jitter, everything that
failed during an outage retries in lockstep the moment it recovers and knocks the dependency over
again.

**The DLQ record carries** the original event, the topic, partition and offset, the failure
classification, an exception _type_ and sanitised message, the attempt count, and the failure
timestamp. It **never** carries a stack trace, a secret, or an unsanitised payload fragment — a DLQ
is long-lived storage that operations staff read.

**Reprocessing is an authorized, audited operation**, not a script someone runs. It is exposed as
an endpoint requiring an administrator role, and every invocation is written to the audit log.

**Consumer lag and DLQ depth are metrics from the start.** They are the two numbers that say
whether the pipeline is healthy, and they are what the console's health screen and Phase 7's
dashboards are built on.

## Alternatives considered

**A schema registry (Confluent, Apicurio) with Avro or Protobuf.** Rejected for v1. It adds a
service to run, a serialisation format that is not human-readable in a console consumer, and a
compatibility mechanism this project can express with JSON Schema files in the repository. The
registry earns its keep when many teams evolve schemas independently; here one repository holds
every producer and consumer, so the pull request _is_ the compatibility check. Revisit if a
consumer ever lives outside this repository.

**Kafka transactions instead of an outbox.** Rejected: it couples request handling to broker
availability, and the outbox is the pattern this project exists to demonstrate.

**Exactly-once semantics.** Rejected as a goal. End-to-end exactly-once across a database, a
broker, and an HTTP call to a scoring service is not achievable without distributed transactions;
at-least-once plus idempotent consumers achieves the same observable outcome and is honest about
how.

**Keying transaction events by transaction UUID** for even partition distribution. Rejected: it
destroys per-account ordering, which velocity rules require.

**One topic with an `eventType` discriminator.** Rejected: consumers would deserialise and discard
most of what they read, retention could not differ per event type, and per-topic lag — the most
useful operational signal — would be unavailable.

## Consequences

**Positive.** Producers and consumers can be tested against a file rather than each other. A
consumer can be restarted, or replayed from an earlier offset, without producing duplicate
side-effects. Failures are classified rather than retried indiscriminately. The ordering guarantee
is stated, so nothing quietly depends on a stronger one.

**Negative.** The outbox adds a table, a relay, and a publication delay measured in the relay's
poll interval. Idempotent consumption adds a table and a write per event. JSON Schema validation is
slower than a binary format — irrelevant at this scale, and the reason a registry is not needed.
Per-account keying means an account with disproportionate volume creates a hot partition.

**Revisit if:** a consumer appears outside this repository, at which point a schema registry starts
earning its cost; measured partition skew becomes a real bottleneck; or event volume makes JSON
serialisation cost visible in a profile rather than in an opinion.
