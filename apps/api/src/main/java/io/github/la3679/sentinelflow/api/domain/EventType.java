/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.domain;

import java.util.Arrays;

/**
 * The five event types SentinelFlow publishes.
 *
 * <p>Closed by design (ADR-0006). A consumer routes on this value without parsing the payload, so
 * adding a type is a contract change with an AsyncAPI update and a schema, not a new string
 * someone writes at a call site.
 *
 * <p>Dots are legal in the wire value and not in a Java identifier, which is why this enum carries
 * an explicit mapping rather than relying on {@code name()}.
 */
public enum EventType {
    TRANSACTION_CREATED("transaction.created", AggregateType.TRANSACTION),
    RISK_ASSESSED("risk.assessed", AggregateType.ASSESSMENT),
    ALERT_CREATED("alert.created", AggregateType.ALERT),
    ALERT_UPDATED("alert.updated", AggregateType.ALERT),
    TRANSACTION_PROCESSING_FAILED("transaction.processing.failed", AggregateType.TRANSACTION);

    private final String wireValue;
    private final AggregateType aggregateType;

    EventType(String wireValue, AggregateType aggregateType) {
        this.wireValue = wireValue;
        this.aggregateType = aggregateType;
    }

    public String wireValue() {
        return wireValue;
    }

    /** The aggregate an event of this type is always about. */
    public AggregateType aggregateType() {
        return aggregateType;
    }

    /**
     * @throws IllegalArgumentException if the value is not one of the five the contract defines
     */
    public static EventType fromWireValue(String value) {
        return Arrays.stream(values())
                .filter(candidate -> candidate.wireValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown event type: " + value));
    }
}
