# ADR-0007 — Money, identifiers, time, and schema migrations

- **Status:** Accepted
- **Date:** 2026-08-25
- **Related:** [ADR-0002](0002-monorepo-and-service-boundaries.md),
  [ADR-0003](0003-java-and-spring-boot-versions.md), [ADR-0006](0006-event-schema-and-versioning.md)

## Context

Phase 2 creates the first database schema. Four representation choices have to be made before that
migration exists, because each one propagates into the database, the API, the event payloads, the
Python service, and the console — and each becomes progressively more expensive to reverse.

This ADR settles money, identifiers, time, and how the schema is allowed to change.

## Decision

### 1. Money is a scaled decimal, everywhere, always

| Layer                 | Representation                                              |
| --------------------- | ----------------------------------------------------------- |
| PostgreSQL            | `NUMERIC(19, 4)`                                            |
| Java                  | `java.math.BigDecimal`                                      |
| Python                | `decimal.Decimal`                                           |
| JSON — API and events | **String**, e.g. `"1234.56"`, never a JSON number           |
| TypeScript            | `string`, formatted for display only at the render boundary |

**No binary floating point touches a monetary value at any point.** Not as a column type, not as an
intermediate in a calculation, not as a JSON number.

The JSON-as-string rule is the part that looks fussy and is not. `JSON.parse` produces a
`double`, so a JSON number is silently rounded by every JavaScript consumer before any application
code sees it. Serialising as a string is the only way the value that arrives is the value that was
sent. Jackson is configured with `WRITE_BIGDECIMAL_AS_PLAIN` so scientific notation never appears,
and Pydantic serialises `Decimal` as a string on the Python side.

`NUMERIC(19, 4)` gives four fractional digits — enough for every ISO 4217 minor unit in use,
including three-decimal currencies like BHD — and 15 integral digits, which is more headroom than
synthetic data will ever need.

**Currency is never implicit.** Every amount is stored and transmitted with an explicit ISO 4217
alphabetic code in an adjacent column or field. An amount without a currency is not a
representation of money; it is a number that looks like one.

**Comparison uses `compareTo`, not `equals`.** `BigDecimal.equals` compares scale as well as
value, so `1.50` and `1.5` are unequal — a bug that shows up as a test that passes locally and
fails after a round trip through the database.

**Rounding is explicit where it happens.** Any operation that can produce more than four fractional
digits states its `RoundingMode`. There is no default worth inheriting silently.

### 2. Identifiers: UUIDv7 for public identity, `BIGINT` never exposed

Every entity carries a **UUIDv7** primary key, stored as PostgreSQL `uuid`.

UUIDv7 rather than UUIDv4: it is time-ordered in its high bits, so inserts append to the B-tree
index instead of scattering across it. UUIDv4 primary keys are a well-known source of index
fragmentation and write amplification at volume, and this project intends to measure ingestion
throughput in Phase 9 — starting with a known-bad choice would make that measurement meaningless.

Sequential `BIGINT` keys are rejected for anything public. They leak volume and ordering to anyone
who can see one identifier, and they make identifiers guessable, which turns a missing
authorization check into an enumeration vulnerability rather than a single leaked record.

**Synthetic business references are separate from primary keys.** `ACC-000123`, `MER-0042`,
`TXN-000517` are human-readable handles for demonstration and support conversation. They are
unique, they are not primary keys, and they are not foreign keys.

**Idempotency keys are client-supplied and unique per account.** Ingestion is at-least-once by
design (ADR-0006), so a duplicate submission must be a no-op returning the original result, not a
second transaction.

### 3. Time is UTC, stored as `timestamptz`, exposed as ISO 8601

Every timestamp column is `timestamptz`. Every timestamp in JSON is ISO 8601 with an explicit
offset: `2026-08-25T21:14:07.123Z`.

`timestamptz` rather than `timestamp`: PostgreSQL's `timestamp` stores no zone and silently
reinterprets a value against whatever the session's `TimeZone` happens to be. `timestamptz`
normalises to UTC on write, which makes the stored value unambiguous regardless of who wrote it.

Java uses `Instant` for a point in time. `LocalDateTime` is banned in the domain model: it names a
wall-clock reading with no zone, which is not a point in time.

**`occurredAt` and `createdAt` are different facts and are stored separately.** A transaction
occurred when the synthetic scenario says it did; the row was created when SentinelFlow ingested
it. Replay depends on that distinction — collapsing them makes every replayed transaction appear
to have happened at import time.

Precision is microseconds, which is what `timestamptz` stores. Nothing depends on finer resolution,
and pretending to nanosecond precision the database cannot keep would be a lie in the schema.

### 4. Flyway owns the schema, forward-only

- **Every schema change is a Flyway migration**, from the first one. Nothing is applied by hand.
- **`spring.jpa.hibernate.ddl-auto` is `validate`.** Never `update`, never `create-drop`. Hibernate
  verifies that the schema matches the mappings and refuses to start otherwise; it never modifies
  anything.
- **A merged migration is immutable.** Flyway checksums applied migrations and fails on a change,
  which is the behaviour we want: correcting a released migration means writing a new one.
- **Versioned migrations only** — `V<n>__<description>.sql`. No repeatable (`R__`) migrations for
  schema: seed data is loaded by application code, not by a migration that silently re-runs.
- **Every migration is tested against real PostgreSQL through Testcontainers**, from an empty
  database. **H2 is not acceptable as evidence that a PostgreSQL migration works** — it does not
  share PostgreSQL's type system, constraint behaviour, or SQL dialect, and a green H2 test against
  a schema PostgreSQL would reject is worse than no test.

### 5. Constraints belong in the database

Referential integrity, `CHECK` constraints, uniqueness, and `NOT NULL` are declared in the schema,
not left to application code. Application validation gives a good error message; the constraint is
what makes the invariant true.

Delete behaviour is stated explicitly on every foreign key rather than inherited. Financial history
is not deleted: `ON DELETE RESTRICT` is the default posture, and anything that looks like a cascade
needs a reason at the point of declaration.

**JSONB is for genuinely variable structure only** — reason-code metadata, event payloads in the
outbox. Entities, relationships and anything queried or constrained live in real columns. Hiding
the domain in a JSON blob gives up every guarantee this section just established.

**No triggers for business behaviour.** Logic that lives in a trigger is invisible to the
application, untestable from it, and invisible in a stack trace.

### 6. Indexes come from measured queries

Indexes are added when a query pattern exists and its plan justifies one, with the reason recorded
in the migration. Every index costs write throughput and storage; adding a speculative set "for
performance" is how a write-heavy ingestion path gets slow.

## Alternatives considered

**Money as an integer count of minor units** (cents), the "always use integers" advice. Rejected:
it fails on three-decimal currencies and on any per-unit rate, it requires every consumer to know
the currency's exponent before it can interpret the number, and PostgreSQL `NUMERIC` already gives
exact decimal arithmetic without that ambiguity. The integer approach exists to avoid floats in
languages with no decimal type; Java, Python and PostgreSQL all have one.

**Money as a JSON number.** Rejected for the reason above: every JavaScript consumer rounds it
before application code runs.

**UUIDv4.** Rejected for index locality.

**`BIGSERIAL` primary keys with a separate public UUID.** Rejected as the extra column, extra
index, and extra mapping buy nothing here. The measured advantage of a narrower key matters at a
scale this project does not reach, and it doubles the number of ways to identify every row.

**`timestamp without time zone` plus a convention that everything is UTC.** Rejected: a convention
enforced by nothing is enforced by nobody. The first tool that connects with a different session
`TimeZone` breaks it silently.

**Hibernate `ddl-auto: update`.** Rejected. It produces an unversioned, unreviewable schema that
differs between environments, and it cannot express a data migration at all.

## Consequences

**Positive.** Monetary values are exact end to end, and a JavaScript client cannot corrupt one.
Identifiers are non-guessable and index-friendly. Timestamps are unambiguous. The schema has one
history that is reviewed like code and reproducible from empty.

**Negative.** Handling money as strings in TypeScript means no arithmetic in the console without a
decimal library — deliberate, since the console should not be doing money arithmetic. UUID keys are
16 bytes against 8 for `BIGINT`, and are harder to read in a log. Forward-only migrations mean a
mistake costs an extra migration rather than an edit.

**Enforcement.** These are not conventions to remember: `ddl-auto: validate` fails startup on
drift, Flyway checksums block edits to applied migrations, database constraints reject bad data
regardless of the writing path, and the Testcontainers suite runs every migration from empty
against real PostgreSQL on every CI run.

**Revisit if:** a currency requiring more than four decimal places enters scope; measured index
behaviour contradicts the UUIDv7 choice; or the project acquires a genuine need for temporal
queries that `timestamptz` cannot express.
