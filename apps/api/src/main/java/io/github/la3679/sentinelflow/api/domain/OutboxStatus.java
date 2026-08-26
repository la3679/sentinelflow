/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.domain;

/**
 * Publication state of an outbox row.
 *
 * <p>{@code FAILED} is terminal for the relay and means a human has to look; it is not a retry
 * state. Retries happen while the row is still {@code PENDING}.
 */
public enum OutboxStatus {
    PENDING,
    PUBLISHED,
    FAILED
}
