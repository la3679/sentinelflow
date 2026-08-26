/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.messaging.consumer;

import io.github.la3679.sentinelflow.api.messaging.EventEnvelope;
import io.github.la3679.sentinelflow.api.messaging.payload.TransactionCreatedPayload;

/**
 * What to do with an accepted transaction, once.
 *
 * <p>A port, so the consumer owns delivery — deduplication, classification, retry, dead-lettering —
 * and a handler owns meaning. The two change for entirely different reasons: the first when the
 * pipeline's failure behaviour changes, the second when the business does.
 *
 * <p><strong>Implementations run inside {@link IdempotentEventProcessor}'s transaction</strong>,
 * alongside the ledger row that records the event as handled. Anything written here is committed
 * with that row or rolled back with it, which is what makes "processed" and "the effect" one fact
 * rather than two that usually agree.
 *
 * <p><strong>Failure is a classification, not just an exception.</strong> Throwing anything ordinary
 * means "try again" and the consumer retries with backoff. Throwing
 * {@link NonRetryableEventException} means "this will fail identically next time" and the record
 * goes straight to the dead-letter topic. Getting that backwards is how one bad record stalls a
 * partition, so it is the handler's decision and not a guess made downstream.
 *
 * <p><strong>There is no implementation in Phase 3, and that is the phase's shape rather than an
 * omission.</strong> The consumer's job here is to prove the pipeline: that an event arrives, is
 * deduplicated, is retried when it should be and dead-lettered when it should not. Scoring — the
 * first thing that will genuinely act on one of these — is Phase 4, and it registers here. The
 * consumer therefore injects a list and dispatches to every implementation, so that arrival is an
 * added bean rather than an edit to the consumer.
 */
public interface TransactionCreatedHandler {

    /**
     * @param envelope the transport fields, for correlation and provenance
     * @param payload the event itself, already validated against the v1 contract
     */
    void handle(EventEnvelope envelope, TransactionCreatedPayload payload);
}
