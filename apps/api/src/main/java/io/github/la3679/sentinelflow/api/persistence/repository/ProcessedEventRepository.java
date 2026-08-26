/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.github.la3679.sentinelflow.api.persistence.entity.ProcessedEvent;
import io.github.la3679.sentinelflow.api.persistence.entity.ProcessedEventId;

/** The consumer-side idempotency ledger: one row per consumer per event it has handled. */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEventId> {

    /**
     * Claims an event for a consumer, or reports that it was already claimed.
     *
     * <p><strong>One statement, not a check followed by a save.</strong> A read-then-write has a
     * window between the two, and at-least-once delivery is precisely the traffic that finds it: two
     * deliveries of the same event, on two partitions' listener threads or two instances, both see
     * no row and both proceed. {@code ON CONFLICT DO NOTHING} closes that window in the database,
     * which is the only place it can be closed.
     *
     * <p><strong>Not exception-driven either.</strong> A plain insert would raise a constraint
     * violation on the duplicate, and a violation marks the surrounding transaction rollback-only in
     * PostgreSQL — so the ordinary case of "we have seen this before, do nothing, commit the
     * offset" could not commit. Returning a row count keeps a duplicate an ordinary answer rather
     * than an error to recover from.
     *
     * <p>Named constraint rather than a column list, so the statement fails loudly if the primary
     * key is ever redefined instead of quietly conflicting on something else.
     *
     * @return 1 when this consumer had not seen the event and now owns it, 0 when it had
     */
    @Modifying
    @Query(value = """
                    INSERT INTO processed_events (consumer_name, event_id)
                    VALUES (:consumerName, :eventId)
                    ON CONFLICT ON CONSTRAINT processed_events_pk DO NOTHING
                    """, nativeQuery = true)
    int claim(@Param("consumerName") String consumerName, @Param("eventId") UUID eventId);
}
