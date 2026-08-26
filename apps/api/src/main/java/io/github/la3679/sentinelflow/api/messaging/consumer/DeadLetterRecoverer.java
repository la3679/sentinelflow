/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.messaging.consumer;

import java.time.Instant;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.stereotype.Component;

import io.github.la3679.sentinelflow.api.domain.AggregateType;
import io.github.la3679.sentinelflow.api.domain.DlqFailureClass;
import io.github.la3679.sentinelflow.api.messaging.EventEnvelope;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Where a record goes when it is not going to be processed.
 *
 * <p>Reached two ways, and the difference is written into the record rather than left to be
 * inferred: a failure classified non-retryable arrives after one attempt with the class its thrower
 * chose, and a retryable one arrives after the budget in {@link ConsumerProperties} ran out, as
 * {@link DlqFailureClass#RETRY_EXHAUSTED}. An operator triages those two differently — the first
 * needs a change before it can ever succeed, the second may simply need its dependency back.
 *
 * <p><strong>Two things happen, in this order.</strong> The transaction is marked as having no
 * assessment coming, then the dead-letter record is published. Database first, because a failed
 * publication means the record is redelivered and the whole recovery runs again: marking is
 * idempotent, and a second dead-letter record is a duplicate the DLQ's readers already tolerate. The
 * other order risks a published failure for a transaction the console still shows as waiting.
 *
 * <p><strong>A record that is not an envelope at all cannot be dead-lettered.</strong>
 * {@code dlq-record.v1.json} requires {@code originalEvent} to be a complete valid envelope, and
 * ADR-0006 §4 forbids copying an unsanitised payload fragment onto a topic operations staff read —
 * so there is nothing legitimate to put in the record. Such a message is logged at error with its
 * exact coordinates, counted under its own metric, and its offset committed. The original bytes stay
 * readable by topic, partition and offset for as long as retention holds them, which is more than a
 * copy elsewhere would give. Blocking the partition for ever instead is the one option not on the
 * table.
 */
@Component
public class DeadLetterRecoverer implements ConsumerRecordRecoverer {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterRecoverer.class);

    private final EventEnvelopeReader reader;
    private final DeadLetterPublisher publisher;
    private final RetryStateTracker retryState;
    private final FailedAssessmentMarker failedAssessments;
    private final MeterRegistry meters;

    public DeadLetterRecoverer(
            EventEnvelopeReader reader,
            DeadLetterPublisher publisher,
            RetryStateTracker retryState,
            FailedAssessmentMarker failedAssessments,
            MeterRegistry meters) {
        this.reader = reader;
        this.publisher = publisher;
        this.retryState = retryState;
        this.failedAssessments = failedAssessments;
        this.meters = meters;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception failure) {
        Throwable cause = unwrap(failure);
        DlqFailureClass failureClass = classify(cause);

        EventEnvelope original;
        try {
            original = reader.readEnvelope(valueOf(record));
        } catch (RuntimeException unreadable) {
            reportUndeliverable(record, cause, unreadable);
            return;
        }

        RetryStateTracker.AttemptState attempts = retryState.stateFor(record);
        DeadLetterRecord deadLetter = new DeadLetterRecord(
                original,
                TransactionCreatedConsumer.CONSUMER_NAME,
                record.topic(),
                record.partition(),
                record.offset(),
                failureClass,
                FailureSanitiser.typeOf(cause),
                FailureSanitiser.sanitise(cause),
                attempts.attempts(),
                attempts.firstFailedAt(),
                Instant.now());

        markNoAssessmentComing(original);
        publisher.publish(keyOf(record), deadLetter);

        // Error rather than warn: every record that reaches here needed a
        // decision the pipeline could not make, which is what an operator is
        // for. A retryable failure on the way here is logged at warn instead.
        log.error(
                "Dead-lettered event {} ({}) from {}-{} offset {} after {} attempt(s): {} [{}]",
                original.eventId(),
                original.eventType(),
                record.topic(),
                record.partition(),
                record.offset(),
                attempts.attempts(),
                failureClass,
                deadLetter.exceptionType());
        counter("sentinelflow.consumer.deadletter", "class", failureClass.name())
                .increment();
    }

    /**
     * Marks the transaction as one the pipeline will not assess.
     *
     * <p>Only for events about a transaction, and only where the aggregate identifier is present. An
     * event about something else has no transaction to mark, and inventing a relationship to keep
     * the code branch-free would be worse than the branch.
     */
    private void markNoAssessmentComing(EventEnvelope original) {
        if (!AggregateType.TRANSACTION.wireValue().equals(original.aggregateType()) || original.aggregateId() == null) {
            return;
        }
        failedAssessments.mark(original.aggregateId());
    }

    /**
     * A failure the consumer classified keeps its own class; anything else got here by exhausting its
     * retries, which is a different fact about a possibly identical exception.
     */
    private static DlqFailureClass classify(Throwable cause) {
        if (cause instanceof NonRetryableEventException classified) {
            return classified.failureClass();
        }
        return DlqFailureClass.RETRY_EXHAUSTED;
    }

    /**
     * Spring Kafka wraps whatever a listener throws. Recording the wrapper would put
     * {@code ListenerExecutionFailedException} in every record's {@code exceptionType} — the one
     * field an operator triages on, and then a constant.
     */
    private static Throwable unwrap(Exception failure) {
        if (failure instanceof ListenerExecutionFailedException wrapper && wrapper.getCause() != null) {
            return wrapper.getCause();
        }
        return failure;
    }

    private void reportUndeliverable(ConsumerRecord<?, ?> record, Throwable cause, RuntimeException unreadable) {
        log.error(
                "Undeliverable record at {}-{} offset {}: not a readable envelope, so it cannot be "
                        + "dead-lettered without copying unsanitised content onto an operational topic. "
                        + "The original bytes remain readable at those coordinates for as long as the "
                        + "topic's retention holds them. Handling failed with {}, re-reading with {}. "
                        + "Offset committed.",
                record.topic(),
                record.partition(),
                record.offset(),
                FailureSanitiser.typeOf(cause),
                FailureSanitiser.typeOf(unreadable));
        counter("sentinelflow.consumer.undeliverable", "topic", record.topic()).increment();
    }

    private static String valueOf(ConsumerRecord<?, ?> record) {
        return record.value() == null ? null : String.valueOf(record.value());
    }

    private static String keyOf(ConsumerRecord<?, ?> record) {
        return record.key() == null ? null : String.valueOf(record.key());
    }

    private Counter counter(String name, String tagKey, String tagValue) {
        return Counter.builder(name)
                .tag("consumer", TransactionCreatedConsumer.CONSUMER_NAME)
                .tag(tagKey, tagValue)
                .description("Records this consumer could not process")
                .register(meters);
    }
}
