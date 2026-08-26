/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.messaging.consumer;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.github.la3679.sentinelflow.api.persistence.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Runs an event's effect at most once per consumer, however many times it is delivered.
 *
 * <p>Delivery is at-least-once (ADR-0006 §4), which means a duplicate is ordinary traffic and not an
 * incident: the outbox republishes anything whose transaction could not commit after the broker
 * acknowledged, and a consumer that dies between its effect and its offset commit sees the record
 * again on restart. Both are correct behaviour by the design, so the consumer has to be the side
 * that makes them harmless.
 *
 * <p><strong>The ledger row and the effect are one transaction, and the row goes first.</strong>
 * That ordering is the whole mechanism:
 *
 * <ul>
 *   <li>The claim is an {@code INSERT ... ON CONFLICT DO NOTHING}, so a second delivery — even one
 *       racing on another thread or another instance — finds the row already there and does nothing.
 *   <li>If the effect then throws, the transaction rolls back and takes the ledger row with it, so
 *       the event is genuinely unprocessed and the retry is genuinely a first attempt. A ledger
 *       written in its own transaction would mark an event handled that had not been, which is
 *       silent data loss wearing the costume of idempotency.
 * </ul>
 *
 * <p><strong>Deduplication is in the database, not in memory.</strong> An in-memory set is empty
 * after a restart, which is exactly when redelivery happens.
 */
@Component
public class IdempotentEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(IdempotentEventProcessor.class);

    private final ProcessedEventRepository ledger;
    private final MeterRegistry meters;

    public IdempotentEventProcessor(ProcessedEventRepository ledger, MeterRegistry meters) {
        this.ledger = ledger;
        this.meters = meters;
    }

    /**
     * Claims the event for this consumer and, if the claim is new, runs the effect.
     *
     * <p>Propagation is the default {@code REQUIRED} rather than {@code REQUIRES_NEW}: an effect that
     * wants to write to the database must write inside this transaction, because that is what makes
     * "processed" and "the thing it did" a single fact.
     *
     * @param effect what to do the first time. Anything it throws propagates, having rolled the
     *     claim back with it.
     * @return true when the effect ran, false when this consumer had already handled the event
     */
    @Transactional
    public boolean processOnce(String consumerName, UUID eventId, Runnable effect) {
        if (ledger.claim(consumerName, eventId) == 0) {
            log.debug("Consumer {} has already processed event {}; skipping", consumerName, eventId);
            count(consumerName, "duplicate");
            return false;
        }

        effect.run();
        count(consumerName, "processed");
        return true;
    }

    private void count(String consumerName, String outcome) {
        Counter.builder("sentinelflow.consumer.events")
                .tag("consumer", consumerName)
                .tag("outcome", outcome)
                .description("Events a consumer handled, by what it did with them")
                .register(meters)
                .increment();
    }
}
