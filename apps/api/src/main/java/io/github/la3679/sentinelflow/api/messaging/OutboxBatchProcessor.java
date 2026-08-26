/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.messaging;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.github.la3679.sentinelflow.api.persistence.entity.OutboxEvent;
import io.github.la3679.sentinelflow.api.persistence.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * One drain of the outbox: claim a batch, publish it, record what happened.
 *
 * <p><strong>The claim, the publish and the status update are one transaction</strong> (ADR-0005).
 * A relay that dies mid-batch therefore rolls back and the events are simply due again. That is the
 * at-least-once side of the bargain being paid deliberately rather than by accident — a row is
 * marked {@code PUBLISHED} only after the broker has acknowledged it, and if the transaction cannot
 * commit after that, the event is published twice and consumers deduplicate on {@code eventId}.
 *
 * <p><strong>A failure does not abandon the batch.</strong> The exception is caught per event and
 * turned into a scheduled retry, so one unpublishable event cannot hold up the twenty behind it. It
 * also means a partly-failed batch still commits: the successes are recorded, the failures are
 * rescheduled, and nothing is lost either way.
 *
 * <p>Separate from {@link OutboxRelay} so a test can drive exactly one drain and assert on the
 * result, instead of racing a scheduler.
 */
@Component
public class OutboxBatchProcessor {

    private static final Logger log = LoggerFactory.getLogger(OutboxBatchProcessor.class);

    /** Keeps a broker's error message from becoming an unbounded write to a varchar(1000). */
    private static final int MAX_ERROR_LENGTH = 500;

    private final OutboxEventRepository outbox;
    private final EventPublisher publisher;
    private final OutboxProperties properties;
    private final Counter published;
    private final Counter failed;
    private final Timer publishTimer;

    public OutboxBatchProcessor(
            OutboxEventRepository outbox, EventPublisher publisher, OutboxProperties properties, MeterRegistry meters) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.properties = properties;
        this.published = Counter.builder("sentinelflow.outbox.publish")
                .tag("outcome", "success")
                .description("Outbox events successfully published to the broker")
                .register(meters);
        this.failed = Counter.builder("sentinelflow.outbox.publish")
                .tag("outcome", "failure")
                .description("Outbox publication attempts that failed")
                .register(meters);
        this.publishTimer = Timer.builder("sentinelflow.outbox.publish.duration")
                .description("Time spent publishing one outbox event")
                .register(meters);
    }

    /**
     * Drains one batch.
     *
     * @return how many events were claimed. Zero means there was nothing due, which is the steady
     *     state.
     */
    @Transactional
    public int drainOnce() {
        Instant now = Instant.now();
        List<OutboxEvent> batch = outbox.claimDue(now, properties.batchSize());

        for (OutboxEvent event : batch) {
            attemptPublication(event, now);
        }
        return batch.size();
    }

    private void attemptPublication(OutboxEvent event, Instant now) {
        Timer.Sample sample = Timer.start();
        try {
            publisher.publish(event);
            sample.stop(publishTimer);
            // Sets published_at in the same call that sets the status, because
            // the database requires the two to agree: a PUBLISHED row without a
            // time cannot answer how far behind the outbox was.
            event.markPublished(Instant.now());
            published.increment();
        } catch (RuntimeException failure) {
            sample.stop(publishTimer);
            this.failed.increment();
            recordFailure(event, failure, now);
        }
    }

    private void recordFailure(OutboxEvent event, RuntimeException failure, Instant now) {
        String error = sanitise(failure);
        // attemptCount is the count *before* this attempt is recorded, so the
        // comparison is against the attempt that has just failed.
        boolean exhausted = event.getAttemptCount() + 1 >= properties.maxAttempts();

        if (exhausted) {
            event.markFailed(error);
            // The only place this is logged at error level. A retryable failure
            // is expected traffic; giving up is an operator's problem.
            log.error(
                    "Outbox event {} ({}) gave up after {} attempts: {}",
                    event.getId(),
                    event.getEventType().wireValue(),
                    properties.maxAttempts(),
                    error);
            return;
        }

        Instant retryAt = now.plus(backoffFor(event.getAttemptCount()));
        event.markAttemptFailed(error, retryAt);
        log.warn(
                "Outbox event {} failed attempt {}, retrying at {}: {}",
                event.getId(),
                event.getAttemptCount(),
                retryAt,
                error);
    }

    /**
     * Exponential backoff with <strong>full jitter</strong> (ADR-0005).
     *
     * <p>Without jitter, everything that failed during a broker outage retries in lockstep the
     * instant it recovers and knocks it over again: the outage synchronises the retries, and the
     * retries extend the outage. Full jitter — a uniform draw across the whole window rather than a
     * fixed delay at its end — spreads them out.
     *
     * @param attempt how many attempts have already failed
     */
    Duration backoffFor(int attempt) {
        long baseMillis = properties.retryBase().toMillis();
        long ceilingMillis = properties.retryMaxDelay().toMillis();

        // Shift rather than Math.pow, and capped before the shift so a large
        // attempt count cannot overflow into a negative delay.
        long window = attempt >= 32 ? ceilingMillis : Math.min(baseMillis << attempt, ceilingMillis);
        // Bound of at least 1: nextLong requires a positive bound, and a
        // zero-length window would make the jitter a no-op anyway.
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(1, Math.max(2, window)));
    }

    /**
     * What goes in {@code last_error}.
     *
     * <p>An exception type and a truncated message. Never a stack trace, never a payload fragment,
     * never a cause chain: this column lives in a table an operator reads, and
     * [ADR-0005] applies the same rule to it that ADR-0006 applies to a dead-letter record.
     */
    private static String sanitise(RuntimeException failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        if (message.length() > MAX_ERROR_LENGTH) {
            message = message.substring(0, MAX_ERROR_LENGTH) + "…";
        }
        return failure.getClass().getSimpleName() + (message.isEmpty() ? "" : ": " + message);
    }
}
