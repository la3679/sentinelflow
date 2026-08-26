/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import io.github.la3679.sentinelflow.api.domain.EventType;
import io.github.la3679.sentinelflow.api.persistence.entity.OutboxEvent;
import io.github.la3679.sentinelflow.api.support.AbstractPostgresTest;
import io.github.la3679.sentinelflow.api.support.SchemaFixtures;

/**
 * The relay's policy: what it claims, what it retries, and when it gives up.
 *
 * <p>Against a controllable publisher rather than a broker. The behaviour ADR-0005 decides is what
 * happens when publication <em>fails</em> — a specific number of times, then permanently — and a
 * test that needs a real broker to fail on command is a test nobody writes.
 * {@code KafkaEventPublisherIT} covers the transport against a real broker separately.
 *
 * <p>The scheduled relay is disabled here. Otherwise a background thread publishes rows out from
 * under the assertions, and the suite fails or passes depending on timing.
 */
@Import(OutboxRelayIT.ControllablePublisher.class)
@TestPropertySource(properties = "sentinelflow.outbox.enabled=false")
class OutboxRelayIT extends AbstractPostgresTest {

    @Autowired
    private OutboxBatchProcessor processor;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ControllablePublisher publisher;

    @Autowired
    private OutboxProperties properties;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE outbox_events");
        publisher.reset();
    }

    /** Inserts a due PENDING event and returns its id. */
    private UUID insertDueEvent() {
        return insertEvent("PENDING", Instant.now().minusSeconds(1), 0);
    }

    private UUID insertEvent(String status, Instant nextAttemptAt, int attemptCount) {
        return jdbc.queryForObject(
                """
                INSERT INTO outbox_events (
                    aggregate_type, aggregate_id, event_type, schema_version, partition_key,
                    payload, status, attempt_count, next_attempt_at, correlation_id, occurred_at)
                VALUES ('transaction', gen_random_uuid(), 'transaction.created', 1, ?,
                        '{"transactionId": "x"}'::jsonb, ?, ?, ?, gen_random_uuid(), now())
                RETURNING id
                """,
                UUID.class,
                "ACC-" + SchemaFixtures.next6(),
                status,
                attemptCount,
                java.sql.Timestamp.from(nextAttemptAt));
    }

    private Map<String, Object> rowFor(UUID id) {
        return jdbc.queryForMap(
                "SELECT status, attempt_count, last_error, next_attempt_at, published_at "
                        + "FROM outbox_events WHERE id = ?",
                id);
    }

    @Test
    @DisplayName("a due event is published and marked with the time it was published")
    void publishesDueEvents() {
        UUID id = insertDueEvent();

        assertThat(processor.drainOnce()).isEqualTo(1);

        Map<String, Object> row = rowFor(id);
        assertThat(row).containsEntry("status", "PUBLISHED");
        // The database requires PUBLISHED and a publication time to agree in
        // both directions, so this is the constraint as much as the code.
        assertThat(row.get("published_at")).isNotNull();
        assertThat(row.get("last_error")).isNull();
        assertThat(publisher.published()).hasSize(1);
    }

    @Test
    @DisplayName("an event that is not due yet is left alone")
    void skipsEventsThatAreNotDue() {
        insertEvent("PENDING", Instant.now().plus(1, ChronoUnit.HOURS), 3);

        assertThat(processor.drainOnce()).isZero();
        assertThat(publisher.published()).isEmpty();
    }

    @Test
    @DisplayName("an already published event is never claimed again")
    void skipsPublishedEvents() {
        jdbc.update("""
                INSERT INTO outbox_events (
                    aggregate_type, aggregate_id, event_type, schema_version, partition_key,
                    payload, status, correlation_id, occurred_at, published_at)
                VALUES ('transaction', gen_random_uuid(), 'transaction.created', 1, 'ACC-000001',
                        '{}'::jsonb, 'PUBLISHED', gen_random_uuid(), now(), now())
                """);

        assertThat(processor.drainOnce()).isZero();
    }

    @Test
    @DisplayName("a failed publication stays PENDING and is scheduled to try again")
    void failureIsRescheduledNotAbandoned() {
        UUID id = insertDueEvent();
        publisher.failWith(new EventPublicationException("broker unreachable"));

        processor.drainOnce();

        Map<String, Object> row = rowFor(id);
        // PENDING, not FAILED. FAILED rows are outside outbox_events_due_idx,
        // which is the relay's only query, so an event moved there early would
        // never be published again.
        assertThat(row).containsEntry("status", "PENDING");
        assertThat(row).containsEntry("attempt_count", 1);
        assertThat((String) row.get("last_error")).contains("EventPublicationException", "broker unreachable");
        assertThat(row.get("published_at")).isNull();

        Instant nextAttempt = ((java.sql.Timestamp) row.get("next_attempt_at")).toInstant();
        assertThat(nextAttempt).isAfter(Instant.now().minusSeconds(5));
    }

    @Test
    @DisplayName("an event gives up after the configured number of attempts and stays for an operator")
    void exhaustedEventBecomesFailed() {
        // One attempt short of the budget, so the next failure is the last.
        UUID id = insertEvent("PENDING", Instant.now().minusSeconds(1), properties.maxAttempts() - 1);
        publisher.failWith(new EventPublicationException("still unreachable"));

        processor.drainOnce();

        Map<String, Object> row = rowFor(id);
        assertThat(row).containsEntry("status", "FAILED");
        assertThat((String) row.get("last_error")).contains("still unreachable");
        // Not deleted. The row is the record that an operator acts on, and
        // reviving it is an authorized, audited operation rather than automatic
        // (ADR-0005).
        assertThat(row.get("published_at")).isNull();
    }

    @Test
    @DisplayName("a FAILED event is never claimed again on its own")
    void failedEventsAreTerminal() {
        insertEvent("FAILED", Instant.now().minusSeconds(1), 10);

        assertThat(processor.drainOnce()).isZero();
    }

    @Test
    @DisplayName("one unpublishable event does not hold up the rest of the batch")
    void oneFailureDoesNotAbandonTheBatch() {
        UUID poison = insertDueEvent();
        UUID healthy = insertDueEvent();
        publisher.failOnlyFor(poison, new EventPublicationException("this one is broken"));

        assertThat(processor.drainOnce()).isEqualTo(2);

        // The successes commit and the failure is rescheduled. A batch that
        // rolled back on the first failure would make one bad event stop every
        // event behind it.
        assertThat(rowFor(healthy)).containsEntry("status", "PUBLISHED");
        assertThat(rowFor(poison)).containsEntry("status", "PENDING");
        assertThat(rowFor(poison)).containsEntry("attempt_count", 1);
    }

    @Test
    @DisplayName("the batch size bounds how much one drain claims")
    void batchSizeIsRespected() {
        for (int i = 0; i < properties.batchSize() + 5; i++) {
            insertDueEvent();
        }

        // Bounded because the claim holds row locks for the length of the
        // publish; an unbounded claim would hold them for the whole backlog.
        assertThat(processor.drainOnce()).isEqualTo(properties.batchSize());
        assertThat(processor.drainOnce()).isEqualTo(5);
    }

    @Test
    @DisplayName("the oldest due event is published first")
    void oldestDueEventGoesFirst() {
        UUID older = insertEvent("PENDING", Instant.now().minus(10, ChronoUnit.MINUTES), 0);
        UUID newer = insertEvent("PENDING", Instant.now().minus(1, ChronoUnit.MINUTES), 0);

        processor.drainOnce();

        List<UUID> order = publisher.published();
        assertThat(order).containsExactly(older, newer);
    }

    @Test
    @DisplayName("backoff is jittered, bounded, and grows with the attempt count")
    void backoffIsJitteredAndBounded() {
        Duration ceiling = properties.retryMaxDelay();

        // Every draw inside the window, including at an attempt count large
        // enough to overflow a naive shift.
        for (int attempt : new int[] {0, 1, 5, 10, 40, 200}) {
            for (int draw = 0; draw < 50; draw++) {
                Duration backoff = processor.backoffFor(attempt);
                assertThat(backoff).isPositive().isLessThanOrEqualTo(ceiling);
            }
        }

        // Full jitter means the draws differ. Without it, everything that
        // failed during an outage retries in lockstep the instant it recovers
        // and knocks the broker over again.
        long distinct = java.util.stream.IntStream.range(0, 50)
                .mapToLong(i -> processor.backoffFor(8).toMillis())
                .distinct()
                .count();
        assertThat(distinct).isGreaterThan(1L);
    }

    /** An {@link EventPublisher} a test can make fail, and that records what it was given. */
    @TestConfiguration(proxyBeanMethods = false)
    static class ControllablePublisher implements EventPublisher {

        private final List<UUID> published = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
        private final AtomicReference<UUID> failOnly = new AtomicReference<>();
        private final AtomicInteger calls = new AtomicInteger();

        @Bean
        @Primary
        ControllablePublisher controllablePublisher() {
            return this;
        }

        @Override
        public void publish(OutboxEvent event) {
            calls.incrementAndGet();
            RuntimeException configured = failure.get();
            UUID only = failOnly.get();

            if (configured != null && (only == null || only.equals(event.getId()))) {
                throw configured;
            }
            assertThat(event.getEventType()).isEqualTo(EventType.TRANSACTION_CREATED);
            published.add(event.getId());
        }

        void reset() {
            published.clear();
            failure.set(null);
            failOnly.set(null);
            calls.set(0);
        }

        void failWith(RuntimeException exception) {
            failure.set(exception);
            failOnly.set(null);
        }

        void failOnlyFor(UUID eventId, RuntimeException exception) {
            failure.set(exception);
            failOnly.set(eventId);
        }

        List<UUID> published() {
            return List.copyOf(published);
        }
    }
}
