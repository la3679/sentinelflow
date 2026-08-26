/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.messaging.consumer;

import java.util.List;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import io.github.la3679.sentinelflow.api.domain.DlqFailureClass;
import io.github.la3679.sentinelflow.api.domain.EventType;
import io.github.la3679.sentinelflow.api.messaging.EventEnvelope;
import io.github.la3679.sentinelflow.api.messaging.payload.TransactionCreatedPayload;

/**
 * Consumes {@code transaction.created.v1}.
 *
 * <p>The listener owns delivery and nothing else: read the envelope, check it is the event this
 * topic promises, read the payload as the shape its version promises, then hand it to every
 * registered {@link TransactionCreatedHandler} exactly once. What to <em>do</em> with an accepted
 * transaction belongs to a handler, because that changes when the business changes and this changes
 * when the pipeline does.
 *
 * <p><strong>Every validation failure here is non-retryable, and that is the point.</strong> An
 * envelope that will not parse, an {@code eventType} nothing dispatches, a {@code schemaVersion}
 * this build does not understand, a payload of the wrong shape — none of them will differ on a
 * second delivery. Retrying them costs the entire partition queued behind the record and changes
 * nothing (ADR-0006 §4), so they go straight to the dead-letter topic. Anything a handler throws is
 * treated as retryable unless the handler says otherwise, because a handler failure is usually a
 * dependency being briefly unavailable.
 *
 * <p><strong>Correlation is put in the MDC, not into a message.</strong> {@code correlationId} ties
 * the originating HTTP request, the outbox row, this record, and everything a handler logs into one
 * traceable line of work. Setting it once here means a handler's own logging carries it without
 * every handler having to remember to include it; the {@code finally} removes it because listener
 * threads are pooled and a stale value would attribute the next record's logs to the wrong request.
 */
@Component
// Off leaves no listener endpoint registered at all, rather than one that starts
// and cannot reach a broker. That distinction matters: a listener container
// whose bootstrap address does not resolve fails the application context during
// startup, so every test that needs the schema and no broker would fail on a
// Kafka error. On by default, because a consumer that has to be switched on is
// a pipeline that silently does not run.
@ConditionalOnProperty(prefix = "sentinelflow.consumer", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TransactionCreatedConsumer {

    /**
     * The consumer group, and the {@code consumer_name} written to the idempotency ledger.
     *
     * <p>They are deliberately the same string: the group is what Kafka redelivers to, so it is
     * exactly the scope within which "have we already handled this" is the right question. It also
     * satisfies {@code processed_events_consumer_format}, which the ledger enforces.
     */
    public static final String CONSUMER_NAME = "transaction-risk";

    /**
     * <p>A literal because an annotation attribute must be a constant expression, so
     * {@code EventTopics.topicFor(...)} cannot be called here. That leaves the name written twice in
     * the codebase, which is exactly the kind of duplication that drifts silently — so
     * {@code TransactionCreatedConsumerTests} asserts this equals the mapping the relay publishes
     * through. A test is the only thing that can hold the two together at this join.
     */
    static final String TOPIC = "transaction.created.v1";

    /** The only payload version this build understands. A newer one gets a new topic (ADR-0006 §3). */
    private static final int SUPPORTED_SCHEMA_VERSION = 1;

    private static final String CORRELATION_MDC_KEY = "correlationId";

    private static final Logger log = LoggerFactory.getLogger(TransactionCreatedConsumer.class);

    private final EventEnvelopeReader reader;
    private final IdempotentEventProcessor processor;
    private final List<TransactionCreatedHandler> handlers;

    /**
     * @param handlers every registered handler, which in Phase 3 is none. The list is injected rather
     *     than a single handler so that Phase 4's scoring arrives as a new bean instead of an edit
     *     here, and so that a second interested handler later does not turn this into a fan-out
     *     written by hand.
     */
    public TransactionCreatedConsumer(
            EventEnvelopeReader reader, IdempotentEventProcessor processor, List<TransactionCreatedHandler> handlers) {
        this.reader = reader;
        this.processor = processor;
        this.handlers = handlers;
    }

    @KafkaListener(topics = TOPIC, groupId = CONSUMER_NAME)
    public void onRecord(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = reader.readEnvelope(record.value());
        requireExpectedEvent(envelope);

        TransactionCreatedPayload payload = reader.readPayload(envelope.payload(), TransactionCreatedPayload.class);

        MDC.put(CORRELATION_MDC_KEY, String.valueOf(envelope.correlationId()));
        try {
            boolean ran = processor.processOnce(
                    CONSUMER_NAME, envelope.eventId(), () -> handlers.forEach(h -> h.handle(envelope, payload)));
            if (ran) {
                log.debug(
                        "Handled event {} for transaction {} through {} handler(s)",
                        envelope.eventId(),
                        payload.transactionId(),
                        handlers.size());
            }
        } finally {
            MDC.remove(CORRELATION_MDC_KEY);
        }
    }

    /**
     * @throws NonRetryableEventException if this is not a {@code transaction.created} at a version
     *     this build understands
     */
    private static void requireExpectedEvent(EventEnvelope envelope) {
        EventType eventType;
        try {
            eventType = EventType.fromWireValue(envelope.eventType());
        } catch (IllegalArgumentException unknown) {
            throw new NonRetryableEventException(
                    DlqFailureClass.UNKNOWN_EVENT_TYPE, "No dispatch for event type " + envelope.eventType(), unknown);
        }

        if (eventType != EventType.TRANSACTION_CREATED) {
            // A known type on the wrong topic. Not this consumer's to handle,
            // and nothing else is reading this topic, so it cannot simply be
            // skipped without losing it silently.
            throw new NonRetryableEventException(
                    DlqFailureClass.UNKNOWN_EVENT_TYPE, "Event type " + envelope.eventType() + " arrived on " + TOPIC);
        }

        if (envelope.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            throw new NonRetryableEventException(
                    DlqFailureClass.SCHEMA_VALIDATION_FAILED,
                    "Schema version " + envelope.schemaVersion() + " is not supported; this build reads v"
                            + SUPPORTED_SCHEMA_VERSION);
        }
    }
}
