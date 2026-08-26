/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;

/**
 * The other half of the at-least-once bargain: proof that a consumer has already handled an event.
 *
 * <p>The outbox may publish the same event more than once, so a duplicate delivery is normal
 * traffic rather than an error. The composite primary key <em>is</em> the deduplication: inserting
 * it as part of the same transaction as the effect makes a second delivery a constraint violation
 * to swallow rather than a second effect to undo.
 *
 * <p>No setters, and nothing here is updatable. A ledger entry that can be rewritten proves
 * nothing.
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @EmbeddedId
    private ProcessedEventId id;

    @CreationTimestamp
    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    protected ProcessedEvent() {}

    public ProcessedEvent(String consumerName, UUID eventId) {
        this.id = new ProcessedEventId(consumerName, eventId);
    }

    public ProcessedEventId getId() {
        return id;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
