/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import io.github.la3679.sentinelflow.api.domain.AggregateType;
import io.github.la3679.sentinelflow.api.domain.EventType;
import io.github.la3679.sentinelflow.api.domain.OutboxStatus;
import io.github.la3679.sentinelflow.api.observability.TraceStamp;

/**
 * An event waiting to be published, written in the same commit as the change it describes.
 *
 * <p>Writing to PostgreSQL and then to Kafka is two commits with a window between them, and every
 * crash in that window either loses an event or publishes one for a transaction that rolled back.
 * The outbox row closes the window: it is part of the business transaction, and a relay publishes
 * it afterwards - possibly more than once, which is why consumers deduplicate on the event id.
 *
 * <p><strong>The primary key is the event id.</strong> It is not a surrogate: this is the value
 * carried in the envelope and the value consumers deduplicate on. Two identifiers for one event
 * would give a duplicate two identities and defeat the deduplication entirely.
 *
 * <p><strong>{@code partitionKey} is stored, not derived at publication time.</strong> The relay
 * therefore cannot change partitioning by changing a getter, and a record pulled out of a
 * dead-letter queue still says how it was keyed.
 *
 * <p>The relay itself, its retry policy and its dead-letter routing are Phase 3. This entity exists
 * now because the schema is what Phase 2 gates on.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent extends AbstractEntity {

    /**
     * Stored with the contract's spelling - {@code transaction}, not {@code TRANSACTION} - by
     * {@code AggregateTypeConverter}, which applies automatically.
     */
    @Column(name = "aggregate_type", nullable = false, length = 16, updatable = false)
    private AggregateType aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    /** Likewise {@code transaction.created}, via {@code EventTypeConverter}. */
    @Column(name = "event_type", nullable = false, length = 48, updatable = false)
    private EventType eventType;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion;

    @Column(name = "partition_key", nullable = false, length = 64, updatable = false)
    private String partitionKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "correlation_id", nullable = false, updatable = false)
    private UUID correlationId;

    @Column(name = "trace_id", length = 32, updatable = false)
    private String traceId;

    /**
     * The W3C traceparent of the request that caused this event.
     *
     * <p>Stored beside the trace id rather than derived from it, because continuing a trace needs
     * the parent span as well — see {@code V11__outbox_trace_parent.sql} for why the outbox is the
     * one place in this system that has to carry it.
     */
    @Column(name = "trace_parent", length = 55, updatable = false)
    private String traceParent;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEvent() {}

    @SuppressWarnings("checkstyle:ParameterNumber")
    public OutboxEvent(
            EventType eventType,
            UUID aggregateId,
            int schemaVersion,
            String partitionKey,
            String payload,
            UUID correlationId,
            TraceStamp trace,
            Instant occurredAt) {
        // The aggregate an event is about is a property of its type, not a
        // separate decision a caller can get wrong.
        this.aggregateType = eventType.aggregateType();
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.schemaVersion = schemaVersion;
        this.partitionKey = partitionKey;
        this.payload = payload;
        this.correlationId = correlationId;
        // One value rather than two parameters, so a caller cannot write a row
        // the database would refuse for disagreeing with itself.
        this.traceId = trace.traceId();
        this.traceParent = trace.traceParent();
        this.occurredAt = occurredAt;
        this.status = OutboxStatus.PENDING;
        this.attemptCount = 0;
        this.nextAttemptAt = occurredAt;
    }

    /**
     * Records a successful publication. Sets the publication time in the same call that sets the
     * status, because the database requires the two to agree: a PUBLISHED row without one cannot
     * answer how far behind the outbox was, which is the one operational question it exists for.
     */
    public void markPublished(Instant at) {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = at;
        this.lastError = null;
    }

    /**
     * Records a failed attempt and when to try again. The row stays PENDING: a failure that is
     * still going to be retried is not a terminal state, and marking it FAILED would take it out of
     * the relay's index.
     */
    public void markAttemptFailed(String error, Instant retryAt) {
        this.attemptCount++;
        this.lastError = error;
        this.nextAttemptAt = retryAt;
    }

    /** Gives up on this event. Terminal, and left for an operator to see rather than deleted. */
    public void markFailed(String error) {
        this.status = OutboxStatus.FAILED;
        this.lastError = error;
    }

    public AggregateType getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public EventType getEventType() {
        return eventType;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public String getPartitionKey() {
        return partitionKey;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public String getTraceId() {
        return traceId;
    }

    /** The traceparent to replay onto the record at publication, or null if there was no trace. */
    public String getTraceParent() {
        return traceParent;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
