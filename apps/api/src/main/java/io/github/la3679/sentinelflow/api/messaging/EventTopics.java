/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.messaging;

import java.util.Map;

import io.github.la3679.sentinelflow.api.domain.EventType;

/**
 * Which topic each event type is published to.
 *
 * <p>Names come from {@code contracts/asyncapi/sentinelflow-events.yaml} and carry an explicit
 * {@code .v1} suffix. That suffix is the escape hatch ADR-0006 relies on: a breaking payload change
 * publishes to {@code .v2} alongside {@code .v1} so consumers migrate on their own schedule, rather
 * than every consumer having to be redeployed at the same instant as the producer.
 *
 * <p>A topic name reaches every consumer's configuration, so this map is a contract and not a
 * constant. {@code EventTopicsTests} asserts it covers every {@link EventType}, because a type
 * added without a topic would fail at publication time — inside the relay, on a row already
 * committed, where the only symptom is an event that never arrives.
 */
public final class EventTopics {

    private static final Map<EventType, String> TOPICS = Map.of(
            EventType.TRANSACTION_CREATED, "transaction.created.v1",
            EventType.RISK_ASSESSED, "risk.assessed.v1",
            EventType.ALERT_CREATED, "alert.created.v1",
            EventType.ALERT_UPDATED, "alert.updated.v1",
            EventType.TRANSACTION_PROCESSING_FAILED, "transaction.processing.dlq.v1");

    private EventTopics() {}

    /**
     * @throws IllegalArgumentException if the type has no topic, which is a defect in this class
     *     rather than in the caller
     */
    public static String topicFor(EventType eventType) {
        String topic = TOPICS.get(eventType);
        if (topic == null) {
            throw new IllegalArgumentException("No topic is mapped for event type " + eventType);
        }
        return topic;
    }

    /** Every topic this service publishes to. */
    public static Map<EventType, String> all() {
        return TOPICS;
    }
}
