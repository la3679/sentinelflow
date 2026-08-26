/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.la3679.sentinelflow.api.support.AbstractPostgresTest;
import io.github.la3679.sentinelflow.api.support.SchemaFixtures;

/**
 * The at-most-once guarantee, against the database that actually provides it.
 *
 * <p>Everything here is a property of PostgreSQL rather than of the Java: the claim is an
 * {@code INSERT ... ON CONFLICT DO NOTHING}, and the reason the effect is safe is that both live in
 * one transaction. A test with a mocked repository would assert that this class calls the methods it
 * calls, which is a restatement of the code and not a test of the guarantee.
 */
class IdempotentEventProcessorIT extends AbstractPostgresTest {

    private static final String CONSUMER = "test-consumer";

    @Autowired
    private IdempotentEventProcessor processor;

    @Autowired
    private JdbcTemplate jdbc;

    private SchemaFixtures fixtures;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE processed_events");
        fixtures = new SchemaFixtures(jdbc);
    }

    private long ledgerRows(UUID eventId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM processed_events WHERE consumer_name = ? AND event_id = ?",
                Long.class,
                CONSUMER,
                eventId);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("the effect runs on the first delivery and not on the second")
    void runsTheEffectOnce() {
        UUID eventId = UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();

        assertThat(processor.processOnce(CONSUMER, eventId, runs::incrementAndGet))
                .isTrue();
        assertThat(processor.processOnce(CONSUMER, eventId, runs::incrementAndGet))
                .isFalse();

        assertThat(runs).hasValue(1);
        assertThat(ledgerRows(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("two consumers each handle the same event, because the ledger is scoped per consumer")
    void consumersDoNotSuppressEachOther() {
        UUID eventId = UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();

        assertThat(processor.processOnce("first-consumer", eventId, runs::incrementAndGet))
                .isTrue();
        assertThat(processor.processOnce("second-consumer", eventId, runs::incrementAndGet))
                .isTrue();

        // A global uniqueness constraint would let whichever ran first silently
        // suppress the other, which is a lost event wearing the costume of
        // deduplication.
        assertThat(runs).hasValue(2);
    }

    @Test
    @DisplayName("a failing effect takes the ledger row with it, so the retry is genuinely a first attempt")
    void rollsBackTheClaimWithTheEffect() {
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> processor.processOnce(CONSUMER, eventId, () -> {
                    throw new IllegalStateException("the scoring service is down");
                }))
                .isInstanceOf(IllegalStateException.class);

        // The whole point of writing the row inside the effect's transaction. A
        // ledger written separately would mark this event handled when nothing
        // had happened, and the redelivery would then be skipped: silent loss.
        assertThat(ledgerRows(eventId)).isZero();

        AtomicInteger runs = new AtomicInteger();
        assertThat(processor.processOnce(CONSUMER, eventId, runs::incrementAndGet))
                .isTrue();
        assertThat(runs).hasValue(1);
    }

    @Test
    @DisplayName("the effect writes in the same transaction as the ledger row")
    void effectAndLedgerCommitTogether() {
        UUID eventId = UUID.randomUUID();
        UUID transactionId = fixtures.insertTransaction();

        assertThatThrownBy(() -> processor.processOnce(CONSUMER, eventId, () -> {
                    jdbc.update("UPDATE transactions SET processing_status = 'FAILED' WHERE id = ?", transactionId);
                    throw new IllegalStateException("after the write, before the commit");
                }))
                .isInstanceOf(IllegalStateException.class);

        // Not just the ledger: the effect's own write is gone too, which is what
        // "processed and the thing it did are one fact" has to mean.
        assertThat(jdbc.queryForObject(
                        "SELECT processing_status FROM transactions WHERE id = ?", String.class, transactionId))
                .isEqualTo("PENDING");
        assertThat(ledgerRows(eventId)).isZero();
    }

    @Test
    @DisplayName("two threads racing on one event produce one effect, because the database decides")
    void concurrentDeliveriesRaceSafely() throws InterruptedException {
        UUID eventId = UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger claims = new AtomicInteger();
        int threads = 8;

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (processor.processOnce(CONSUMER, eventId, runs::incrementAndGet)) {
                        claims.incrementAndGet();
                    }
                });
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            // Released together, so the claims genuinely overlap rather than
            // running one after another and proving nothing about the race.
            go.countDown();
        }

        // A read-then-write would let several of these through: they would all
        // see no row before any of them wrote one.
        assertThat(claims).hasValue(1);
        assertThat(runs).hasValue(1);
        assertThat(ledgerRows(eventId)).isEqualTo(1);
    }
}
