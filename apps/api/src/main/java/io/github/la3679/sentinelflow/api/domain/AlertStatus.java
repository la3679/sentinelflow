/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.domain;

/**
 * Alert lifecycle state.
 *
 * <p>Listing a state here does not make every transition into it legal. The legal transitions are
 * a property of the alert service, delivered with the workflow that enforces them.
 */
public enum AlertStatus {
    NEW,
    IN_REVIEW,
    ESCALATED,
    CONFIRMED_SUSPICIOUS,
    DISMISSED_FALSE_POSITIVE,
    CLOSED
}
