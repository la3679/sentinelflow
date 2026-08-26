-- V6 - the messaging and audit substrate.
--
-- outbox_events is what makes "the row was written" and "the event was
-- published" one atomic fact. Writing to PostgreSQL and then to Kafka is two
-- commits with a window between them, and every crash in that window either
-- loses an event or publishes one for a transaction that rolled back. The
-- outbox row is written inside the same database transaction as the business
-- change; a relay publishes it afterwards and may publish it more than once,
-- which is why consumers deduplicate on eventId.
--
-- processed_events is the other half of that bargain: at-least-once delivery
-- means a duplicate is normal traffic, and the composite primary key is what
-- makes a second delivery a no-op rather than a second effect.
--
-- The relay itself, its retry policy and its dead-letter routing are Phase 3.
-- The schema lands here because the schema is what Phase 2 gates on, and
-- because a table added later would have to be added while events are already
-- flowing.

CREATE TABLE outbox_events (
    -- Not a surrogate: this IS the eventId carried in the envelope and the
    -- value consumers deduplicate on. Two identifiers for one event would give
    -- a duplicate two identities.
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),

    aggregate_type  varchar(16) NOT NULL,
    aggregate_id    uuid        NOT NULL,
    event_type      varchar(48) NOT NULL,
    schema_version  integer     NOT NULL,

    -- The Kafka message key. Stored rather than derived at publication time so
    -- the relay cannot silently change partitioning by changing a getter, and
    -- so a record read out of a dead-letter queue still says how it was keyed.
    partition_key   varchar(64) NOT NULL,

    payload         jsonb       NOT NULL,

    status          varchar(16) NOT NULL DEFAULT 'PENDING',
    attempt_count   integer     NOT NULL DEFAULT 0,
    last_error      varchar(1000),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),

    correlation_id  uuid        NOT NULL,
    trace_id        varchar(32),

    occurred_at     timestamptz NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    published_at    timestamptz,

    CONSTRAINT outbox_events_aggregate_type_known
        CHECK (aggregate_type IN ('transaction', 'assessment', 'alert')),
    CONSTRAINT outbox_events_type_known CHECK (event_type IN (
        'transaction.created', 'risk.assessed', 'alert.created', 'alert.updated',
        'transaction.processing.failed')),
    CONSTRAINT outbox_events_schema_version_positive CHECK (schema_version >= 1),
    CONSTRAINT outbox_events_status_known CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT outbox_events_attempts_nonnegative CHECK (attempt_count >= 0),
    CONSTRAINT outbox_events_payload_object CHECK (jsonb_typeof(payload) = 'object'),
    -- W3C trace context trace-id: 32 lower-case hex characters, or absent.
    CONSTRAINT outbox_events_trace_format CHECK (trace_id IS NULL OR trace_id ~ '^[0-9a-f]{32}$'),
    -- Published without a publication time is a row that cannot answer how long
    -- the outbox was behind, which is the one operational question it exists to
    -- answer.
    CONSTRAINT outbox_events_published_at_consistent
        CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL))
);

COMMENT ON TABLE outbox_events IS
    'Transactional outbox. Written in the same commit as the state change it describes.';

-- The relay's only query: the oldest events that are due, and nothing else.
-- Partial and small, because PENDING is a transient state - the published
-- majority never enters this index and never costs a write to maintain it.
CREATE INDEX outbox_events_due_idx ON outbox_events (next_attempt_at, id)
    WHERE status = 'PENDING';

-- Answering "what happened to this alert" from the event side, and the
-- reprocessing path that reads one aggregate's history.
CREATE INDEX outbox_events_aggregate_idx ON outbox_events (aggregate_type, aggregate_id, occurred_at);

CREATE TABLE processed_events (
    -- Per consumer, not global. Two consumers legitimately process the same
    -- event, and a global uniqueness constraint would let whichever ran first
    -- silently suppress the other.
    consumer_name varchar(64) NOT NULL,
    event_id      uuid        NOT NULL,
    processed_at  timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT processed_events_pk PRIMARY KEY (consumer_name, event_id),
    CONSTRAINT processed_events_consumer_format CHECK (consumer_name ~ '^[a-z][a-z0-9.-]{2,63}$')
);

COMMENT ON TABLE processed_events IS
    'Idempotency ledger for consumers. The composite key is the deduplication.';

-- Retention: this table grows with every event processed, and old rows stop
-- being useful once redelivery is no longer possible. The sweep that trims it
-- reads by time.
CREATE INDEX processed_events_processed_at_idx ON processed_events (processed_at);

CREATE TABLE audit_log (
    id             uuid        PRIMARY KEY DEFAULT uuidv7(),

    actor_type     varchar(16) NOT NULL,
    actor_id       uuid,

    action         varchar(64) NOT NULL,
    resource_type  varchar(32) NOT NULL,
    resource_id    uuid,

    -- Sanitised metadata only. Never a credential, never a raw payload, never
    -- anything that would make the audit log the most sensitive table in the
    -- database - which is what happens when "before and after" is taken to mean
    -- "the whole row".
    before_state   jsonb,
    after_state    jsonb,

    correlation_id uuid        NOT NULL,
    trace_id       varchar(32),
    occurred_at    timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT audit_log_actor_type_known CHECK (actor_type IN ('USER', 'SYSTEM')),
    -- A user action with no user is not attributable, and an unattributable
    -- audit entry is not an audit entry.
    CONSTRAINT audit_log_user_attributed CHECK (actor_type <> 'USER' OR actor_id IS NOT NULL),
    CONSTRAINT audit_log_action_format CHECK (action ~ '^[A-Z][A-Z0-9_]{2,63}$'),
    CONSTRAINT audit_log_before_object CHECK (before_state IS NULL OR jsonb_typeof(before_state) = 'object'),
    CONSTRAINT audit_log_after_object CHECK (after_state IS NULL OR jsonb_typeof(after_state) = 'object'),
    CONSTRAINT audit_log_trace_format CHECK (trace_id IS NULL OR trace_id ~ '^[0-9a-f]{32}$'),

    CONSTRAINT audit_log_actor_fk FOREIGN KEY (actor_id) REFERENCES users (id) ON DELETE RESTRICT
);

COMMENT ON TABLE audit_log IS
    'Append-only. Sanitised metadata only; never a credential, a raw payload, or personal data.';

-- The two questions asked of an audit log: what happened to this resource, and
-- what did this actor do.
CREATE INDEX audit_log_resource_idx ON audit_log (resource_type, resource_id, occurred_at DESC);
CREATE INDEX audit_log_actor_idx ON audit_log (actor_id, occurred_at DESC);
