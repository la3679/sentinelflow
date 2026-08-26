/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where {@code attemptCount} and {@code firstFailedAt} in a dead-letter record come from.
 *
 * <p>Both are required by {@code dlq-record.v1.json} and neither is available at the point the
 * record is written, so they are captured on the way past. The properties worth pinning are that the
 * first timestamp is the one kept, that two records on one partition do not inherit each other's
 * history, and that a recovered record stops being tracked — the last being the difference between a
 * bounded map and a slow memory leak.
 */
class RetryStateTrackerTests {

    private static ConsumerRecord<String, String> recordAt(int partition, long offset) {
        return new ConsumerRecord<>("transaction.created.v1", partition, offset, "key", "{}");
    }

    @Test
    @DisplayName("the first failure's timestamp is kept while the attempt count moves")
    void keepsTheFirstTimestamp() throws InterruptedException {
        RetryStateTracker tracker = new RetryStateTracker();
        ConsumerRecord<String, String> record = recordAt(0, 42);

        tracker.failedDelivery(record, new IllegalStateException("first"), 1);
        var afterFirst = tracker.stateFor(record);

        // Enough for the clock to move on any platform this runs on.
        Thread.sleep(5);
        tracker.failedDelivery(record, new IllegalStateException("second"), 2);
        var afterSecond = tracker.stateFor(record);

        assertThat(afterSecond.firstFailedAt()).isEqualTo(afterFirst.firstFailedAt());
        assertThat(afterSecond.attempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("two records on one partition fail independently")
    void tracksRecordsSeparately() {
        RetryStateTracker tracker = new RetryStateTracker();
        ConsumerRecord<String, String> first = recordAt(0, 1);
        ConsumerRecord<String, String> second = recordAt(0, 2);

        tracker.failedDelivery(first, new IllegalStateException("a"), 1);
        tracker.failedDelivery(first, new IllegalStateException("a"), 2);
        tracker.failedDelivery(second, new IllegalStateException("b"), 1);

        // A partition-only key would let the second record inherit the first's
        // two attempts and report a history it never had.
        assertThat(tracker.stateFor(first).attempts()).isEqualTo(2);
        assertThat(tracker.stateFor(second).attempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("a recovered record is forgotten, so the map holds only what is mid-retry")
    void forgetsRecoveredRecords() {
        RetryStateTracker tracker = new RetryStateTracker();
        ConsumerRecord<String, String> record = recordAt(3, 7);

        tracker.failedDelivery(record, new IllegalStateException("boom"), 4);
        tracker.recovered(record, new IllegalStateException("boom"));

        // Back to the default a never-seen record gets: one attempt, now.
        assertThat(tracker.stateFor(record).attempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("a failed recovery keeps its history, because the record has not stopped failing")
    void keepsHistoryWhenRecoveryFails() {
        RetryStateTracker tracker = new RetryStateTracker();
        ConsumerRecord<String, String> record = recordAt(1, 9);

        tracker.failedDelivery(record, new IllegalStateException("boom"), 3);
        tracker.recoveryFailed(record, new IllegalStateException("boom"), new IllegalStateException("dlq down"));

        // The record will be delivered again and dead-lettered again, and the
        // history that belongs in that record is still this one.
        assertThat(tracker.stateFor(record).attempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("a record nobody has seen fail still answers, because the recoverer must not depend on it")
    void unseenRecordHasADefault() {
        assertThat(new RetryStateTracker().stateFor(recordAt(0, 0)).attempts()).isEqualTo(1);
    }
}
