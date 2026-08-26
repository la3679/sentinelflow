/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.github.la3679.sentinelflow.api.domain.OutboxStatus;
import io.github.la3679.sentinelflow.api.persistence.entity.OutboxEvent;

/** The outbox: written by ingestion, drained by the relay. */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Claims a batch of due events for this relay instance and no other.
     *
     * <p><strong>{@code FOR UPDATE SKIP LOCKED} is the whole point</strong> (ADR-0005). {@code FOR
     * UPDATE} takes a row lock, so a second relay cannot select the same rows. {@code SKIP LOCKED}
     * makes it step over rows another instance already holds rather than blocking behind them,
     * which is what lets two instances make progress at once instead of serialising.
     *
     * <p>Without {@code SKIP LOCKED} a second instance waits and then finds nothing to do. Without
     * {@code FOR UPDATE} both publish the same event — which consumers would deduplicate, but that
     * would make the consumer's safety net load-bearing for normal operation rather than for the
     * exception it exists for.
     *
     * <p>The predicate and the ordering match {@code outbox_events_due_idx} exactly, so this is an
     * index range read over the transient {@code PENDING} population rather than a scan of every
     * event ever published.
     *
     * <p>Native SQL because JPQL has no {@code SKIP LOCKED}: {@code @Lock(PESSIMISTIC_WRITE)} plus a
     * timeout hint gives blocking behaviour, not skipping, and the difference is the entire
     * concurrency design.
     */
    @Query(value = """
                    SELECT * FROM outbox_events
                    WHERE status = 'PENDING' AND next_attempt_at <= :now
                    ORDER BY next_attempt_at, id
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED
                    """, nativeQuery = true)
    List<OutboxEvent> claimDue(@Param("now") Instant now, @Param("batchSize") int batchSize);

    long countByStatus(OutboxStatus status);

    /**
     * How far behind the oldest unpublished event is, in seconds, or {@code null} when there is
     * none.
     *
     * <p>Depth alone cannot distinguish a busy relay from a stuck one — a queue of constant size is
     * healthy if it is turning over and broken if it is not. Age can (ADR-0005 §6).
     */
    @Query(value = """
                    SELECT EXTRACT(EPOCH FROM (now() - min(occurred_at)))
                    FROM outbox_events WHERE status = 'PENDING'
                    """, nativeQuery = true)
    Double oldestPendingAgeSeconds();
}
