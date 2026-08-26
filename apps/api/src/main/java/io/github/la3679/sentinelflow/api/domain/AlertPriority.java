/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.domain;

/**
 * How urgently an alert should be looked at.
 *
 * <p>Separate from the risk band: the band describes the score, the priority describes the queue,
 * and an operations team must be able to change one without rewriting the other.
 */
public enum AlertPriority {
    LOW,
    MEDIUM,
    HIGH,
    URGENT
}
