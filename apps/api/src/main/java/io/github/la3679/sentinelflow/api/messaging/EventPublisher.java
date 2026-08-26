/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.messaging;

import io.github.la3679.sentinelflow.api.persistence.entity.OutboxEvent;

/**
 * Where a claimed outbox event goes.
 *
 * <p>A port, so the relay's policy — claiming, backoff, giving up — is testable without a broker.
 * That is not a convenience: the behaviour ADR-0005 actually decides is what happens when
 * publication <em>fails</em>, and a test that needs a real broker to be unreachable in a specific
 * way is a test nobody writes.
 *
 * <p><strong>Publishing must be synchronous and must throw on failure.</strong> The relay marks a
 * row {@code PUBLISHED} only after this returns, inside the same database transaction that claimed
 * it. An implementation that returns before the broker has acknowledged would let the relay record
 * a publication that never happened, which is the exact failure the outbox exists to prevent.
 */
public interface EventPublisher {

    /**
     * Publishes one event, returning only once the broker has acknowledged it.
     *
     * @throws EventPublicationException if the event could not be published. The relay treats this
     *     as retryable and schedules another attempt.
     */
    void publish(OutboxEvent event);
}
