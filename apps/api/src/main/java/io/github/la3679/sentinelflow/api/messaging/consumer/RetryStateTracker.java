/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.messaging.consumer;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.stereotype.Component;

/**
 * Remembers when a record first failed and how many times it has been tried.
 *
 * <p>{@code dlq-record.v1.json} requires {@code attemptCount} and {@code firstFailedAt}, and neither
 * is available at the point the record is written: a recoverer is handed the record and the last
 * exception, and nothing about the history that led there. Spring Kafka offers both to a
 * {@link RetryListener} on every failed delivery, so they are captured where they exist rather than
 * reconstructed where they do not.
 *
 * <p><strong>What this measures, precisely.</strong> {@code firstFailedAt} is the first failure
 * <em>this process</em> saw for this offset. Retries are blocking and in-memory, so for the normal
 * path that is the whole history. It is not, after a restart or a rebalance mid-retry: the record is
 * redelivered to a consumer that has never seen it, and the clock starts again. That is stated here
 * because a timestamp that silently means something narrower than its name is worse than one that
 * does not exist.
 *
 * <p><strong>Bounded on purpose.</strong> Entries are removed on both terminal outcomes, so the map
 * holds only records currently mid-retry — a handful. The one path that leaks is a rebalance that
 * moves a partition away between failures, and {@link #MAX_TRACKED} caps what that can cost: past
 * the cap, entries older than any live retry could be are swept. An unbounded map fed by a
 * per-record key is a memory leak with a slow fuse.
 */
@Component
public class RetryStateTracker implements RetryListener {

    /** Far above any plausible number of simultaneously-retrying records; a backstop, not a limit. */
    static final int MAX_TRACKED = 10_000;

    /** How old an entry must be before a sweep may assume its retry is over. */
    private static final Duration STALE_AFTER = Duration.ofHours(1);

    private final Map<String, AttemptState> inFlight = new ConcurrentHashMap<>();

    @Override
    public void failedDelivery(ConsumerRecord<?, ?> record, Exception failure, int deliveryAttempt) {
        if (inFlight.size() >= MAX_TRACKED) {
            sweep();
        }
        inFlight.merge(
                keyFor(record),
                new AttemptState(Instant.now(), deliveryAttempt),
                // The first failure's timestamp is the one being kept; only the
                // attempt count moves.
                (existing, latest) -> new AttemptState(existing.firstFailedAt(), latest.attempts()));
    }

    @Override
    public void recovered(ConsumerRecord<?, ?> record, Exception failure) {
        inFlight.remove(keyFor(record));
    }

    @Override
    public void recoveryFailed(ConsumerRecord<?, ?> record, Exception original, Exception failure) {
        // Deliberately not removed. Recovery failing means the record will be
        // delivered again and the history that is about to be written is still
        // this one; dropping it here would restart the clock on a record that
        // never stopped failing.
    }

    /**
     * What is known about a record's failures so far.
     *
     * <p>A record classified non-retryable still passes through {@code failedDelivery} once before
     * the recoverer sees it, so the state is present for that case too, with one attempt.
     */
    AttemptState stateFor(ConsumerRecord<?, ?> record) {
        return inFlight.getOrDefault(keyFor(record), new AttemptState(Instant.now(), 1));
    }

    private void sweep() {
        Instant cutoff = Instant.now().minus(STALE_AFTER);
        inFlight.values().removeIf(state -> state.firstFailedAt().isBefore(cutoff));
    }

    private static String keyFor(ConsumerRecord<?, ?> record) {
        // Offset as well as partition: two records on one partition fail
        // independently, and a partition-only key would let the second inherit
        // the first's history.
        return record.topic() + '-' + record.partition() + '-' + record.offset();
    }

    /**
     * @param firstFailedAt when this process first saw this record fail
     * @param attempts how many deliveries have failed, counting the first
     */
    record AttemptState(Instant firstFailedAt, int attempts) {}
}
