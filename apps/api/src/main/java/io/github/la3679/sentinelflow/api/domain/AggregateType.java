/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.domain;

import java.util.Arrays;

/**
 * The kind of entity an event is about.
 *
 * <p>The wire values are lower case, because that is what {@code
 * contracts/schemas/event-envelope.v1.json} says and the contract is authoritative. The constant
 * names are upper case, because that is what a Java constant is. {@link #wireValue()} bridges the
 * two, and {@code AggregateTypeConverter} is what keeps the column in the contract's spelling
 * rather than Java's.
 */
public enum AggregateType {
    TRANSACTION("transaction"),
    ASSESSMENT("assessment"),
    ALERT("alert");

    private final String wireValue;

    AggregateType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    /**
     * @throws IllegalArgumentException if the value is not one the contract defines - an unknown
     *     aggregate type is a contract violation, not a value to tolerate
     */
    public static AggregateType fromWireValue(String value) {
        return Arrays.stream(values())
                .filter(candidate -> candidate.wireValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown aggregate type: " + value));
    }
}
