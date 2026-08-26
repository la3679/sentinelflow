/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.messaging;

import java.time.Instant;
import java.util.UUID;

import io.github.la3679.sentinelflow.api.domain.AggregateType;
import io.github.la3679.sentinelflow.api.domain.EventType;
import io.github.la3679.sentinelflow.api.persistence.entity.OutboxEvent;
import tools.jackson.databind.JsonNode;

/**
 * The envelope every SentinelFlow event is wrapped in (ADR-0006).
 *
 * <p>Field-for-field with {@code contracts/schemas/event-envelope.v1.json}, which sets
 * {@code additionalProperties: false}. {@code EventEnvelopeContractIT} asserts the two have not
 * drifted.
 *
 * <p><strong>Assembled at publication, not at write.</strong> The outbox row stores the payload and
 * the envelope's fields as columns; the relay composes them here. That is what lets the envelope
 * change shape — a v2 envelope, a new transport field — without rewriting rows already in the
 * outbox.
 *
 * <p><strong>{@code payload} is a {@link JsonNode}, not a {@link String}.</strong> The column holds
 * JSON text, and binding it to a string would publish an escaped string where every consumer expects
 * an object. Parsing it here is the difference between {@code "payload": {"amount": ...}} and
 * {@code "payload": "{\"amount\": ...}"}.
 *
 * <p><strong>{@code traceId} is nullable and always present.</strong> Null rather than absent, so a
 * consumer never distinguishes "this event carried no trace context" from "the producer omitted the
 * field". OpenTelemetry arrives in Phase 7; until then this is honestly null rather than fabricated.
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String producer,
        UUID correlationId,
        String traceId,
        String aggregateType,
        UUID aggregateId,
        JsonNode payload) {

    /** Identifies this service as the emitter, for operations and for provenance. */
    public static final String PRODUCER = "sentinelflow-api";

    /**
     * Builds the envelope for a stored outbox row.
     *
     * <p>{@code eventId} is the row's own primary key, not a new identifier. It is the value
     * consumers deduplicate on, so minting a fresh one per publication attempt would give a retried
     * event a second identity and defeat the deduplication entirely.
     */
    public static EventEnvelope of(OutboxEvent event, JsonNode payload) {
        EventType eventType = event.getEventType();
        AggregateType aggregateType = event.getAggregateType();

        return new EventEnvelope(
                event.getId(),
                eventType.wireValue(),
                event.getSchemaVersion(),
                event.getOccurredAt(),
                PRODUCER,
                event.getCorrelationId(),
                event.getTraceId(),
                aggregateType.wireValue(),
                event.getAggregateId(),
                payload);
    }
}
