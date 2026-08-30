-- V11 - the trace context an outbox row has to carry across its own delay.
--
-- V6 gave outbox_events a trace_id, and the column has been null on every row
-- ever written because nothing produced trace context until Phase 7. It is
-- still the right column and it stays: the event envelope declares traceId, so
-- a consumer can name the request an event came from without joining anything.
--
-- WHAT A TRACE ID ALONE CANNOT DO is make one trace out of the two the outbox
-- would otherwise produce.
--
-- The delay is the whole point of the pattern (ADR-0005): an HTTP request
-- writes a row and returns, and a scheduled relay publishes it later, on
-- another thread, often seconds afterwards. By then the request's span is
-- closed. Publishing under whatever context the scheduler happens to be in
-- gives a second, unrelated trace - so a transaction is followable through
-- ingestion, and followable again from the relay onward, with nothing joining
-- the two but a timestamp and a correlation id.
--
-- Continuing the original trace needs the parent SPAN id as well as the trace
-- id, and the W3C traceparent header is exactly the four fields that identify
-- one: version, trace-id, parent-id and trace-flags. Storing the header the
-- request arrived with, and setting it back on the Kafka record at publication,
-- is what makes the consumer's work a descendant of the API call that caused it
-- rather than a sibling of it.
--
-- A SECOND COLUMN RATHER THAN A WIDER trace_id. trace_id is referenced by the
-- event envelope contract and constrained to 32 hex characters; widening it to
-- hold a composite would make a documented field mean something else, and every
-- reader would have to know which of the two shapes a given row predates.
--
-- 55 CHARACTERS, and that is the specification's fixed length rather than a
-- guess: '00' + '-' + 32 + '-' + 16 + '-' + '01'. The check constraint pins the
-- shape, so a malformed value is refused at write time rather than discovered
-- as a broken trace nobody can explain. Version '00' only: a future version may
-- append fields, and a parser that accepted one it had never seen would be
-- guessing at where the fields it needs are.
--
-- NULLABLE, and null is normal. A row written by the seed, by a scheduled job,
-- or by any code path running outside a request has no trace to continue, and a
-- fabricated identifier would be worse than an absent one - it would point an
-- operator at a trace that does not exist.

ALTER TABLE outbox_events
    ADD COLUMN trace_parent varchar(55);

ALTER TABLE outbox_events
    ADD CONSTRAINT outbox_events_trace_parent_format
        CHECK (trace_parent IS NULL
               OR trace_parent ~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$');

-- Both or neither. A trace parent whose trace-id disagrees with the trace_id
-- column would make the envelope and the Kafka header name different traces for
-- one event, which is the kind of inconsistency that costs an hour during an
-- incident and cannot happen if it cannot be written.
ALTER TABLE outbox_events
    ADD CONSTRAINT outbox_events_trace_parent_agrees_with_trace_id
        CHECK (trace_parent IS NULL OR substring(trace_parent from 4 for 32) = trace_id);

COMMENT ON COLUMN outbox_events.trace_parent IS
    'W3C traceparent of the request that caused this event, replayed onto the Kafka record at '
    'publication so the consumer continues that trace rather than starting a new one. Null for a '
    'row written outside any trace.';
