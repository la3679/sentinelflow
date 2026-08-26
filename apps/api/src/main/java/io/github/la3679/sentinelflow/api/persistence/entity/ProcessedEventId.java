/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Composite key for {@link ProcessedEvent}: one row per consumer per event.
 *
 * <p>Per consumer, not global. Two consumers legitimately process the same event, and a global
 * uniqueness constraint would let whichever ran first silently suppress the other.
 */
@Embeddable
public class ProcessedEventId implements Serializable {

    @Column(name = "consumer_name", nullable = false, length = 64, updatable = false)
    private String consumerName;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    protected ProcessedEventId() {}

    public ProcessedEventId(String consumerName, UUID eventId) {
        this.consumerName = consumerName;
        this.eventId = eventId;
    }

    public String getConsumerName() {
        return consumerName;
    }

    public UUID getEventId() {
        return eventId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProcessedEventId that)) {
            return false;
        }
        return consumerName.equals(that.consumerName) && eventId.equals(that.eventId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(consumerName, eventId);
    }
}
