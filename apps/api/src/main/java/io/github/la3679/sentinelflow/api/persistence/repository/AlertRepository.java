/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.github.la3679.sentinelflow.api.domain.AlertPriority;
import io.github.la3679.sentinelflow.api.domain.AlertStatus;
import io.github.la3679.sentinelflow.api.domain.RiskBand;
import io.github.la3679.sentinelflow.api.persistence.entity.Alert;

/** Alerts. */
public interface AlertRepository extends JpaRepository<Alert, UUID> {

    /**
     * Allocates the next alert reference.
     *
     * <p>A sequence, not {@code max(reference) + 1}, for the reason V7 records about transactions:
     * the latter is a read-modify-write that two concurrent writers both win, and the unique
     * constraint then rejects one of them for a reason that has nothing to do with the alert.
     *
     * <p>Gaps are expected. An assessment that rolls back after allocating one consumes a value,
     * because sequences are deliberately not transactional. <strong>Nothing may infer an alert count
     * from the highest reference issued.</strong>
     *
     * <p>Four digits, so this refuses at 9,999 rather than wrapping. V9 explains why that ceiling is
     * left loud instead of widened.
     */
    @Query(value = "SELECT 'ALT-' || lpad(nextval('alert_reference_seq')::text, 4, '0')", nativeQuery = true)
    String nextAlertReference();

    /**
     * The alert an assessment opened, if it opened one.
     *
     * <p>{@code alerts_assessment_unique} makes this at most one row, which is the constraint that
     * stops a retried alert-raising path opening a second alert for one decision.
     */
    Optional<Alert> findByAssessmentId(UUID assessmentId);

    /** Whether a transaction has any alert. Used by the workflow's own tests. */
    boolean existsByTransactionId(UUID transactionId);

    /**
     * The queue: open work first, then the most urgent, then the oldest.
     *
     * <p>Every filter is optional and each is applied only when supplied, which is what lets one
     * query serve the whole queue view. A null parameter matching everything is written as
     * {@code :param IS NULL OR column = :param} rather than as three query methods, because the
     * alternative is eight of them once a third filter arrives.
     *
     * <p><strong>The ordering is the queue's, not a sort the caller chooses.</strong> Open before
     * closed, because a queue is a list of work rather than a list of rows; then priority
     * descending, because that is what the priority is <em>for</em>; then oldest first, because work
     * that has waited longest should be picked up first. A client-supplied sort would let a console
     * quietly reorder a review queue, which is an operational decision rather than a display one.
     *
     * <p>{@code alerts_queue_idx} is {@code (status, priority, created_at DESC)} and serves the
     * common case. The identifier breaks ties so paging is stable across two identical requests.
     */
    @Query("""
            SELECT a FROM Alert a
             WHERE (:status IS NULL OR a.status = :status)
               AND (:priority IS NULL OR a.priority = :priority)
               AND (:assigneeId IS NULL OR a.assigneeId = :assigneeId)
             ORDER BY CASE WHEN a.closedAt IS NULL THEN 0 ELSE 1 END,
                      CASE a.priority
                          WHEN io.github.la3679.sentinelflow.api.domain.AlertPriority.URGENT THEN 0
                          WHEN io.github.la3679.sentinelflow.api.domain.AlertPriority.HIGH THEN 1
                          WHEN io.github.la3679.sentinelflow.api.domain.AlertPriority.MEDIUM THEN 2
                          ELSE 3
                      END,
                      a.createdAt ASC,
                      a.id ASC
            """)
    Page<Alert> findQueue(
            @Param("status") AlertStatus status,
            @Param("priority") AlertPriority priority,
            @Param("assigneeId") UUID assigneeId,
            Pageable pageable);

    // ----------------------------------------------------------------------- //
    // Reporting
    //
    // Half-open windows throughout: created_at >= :from AND < :to, so two
    // adjacent windows neither overlap nor drop the row that falls exactly on
    // the boundary. A closed range would double-count it and an open one would
    // lose it, and both mistakes are invisible in a report.
    //
    // Grouped in the database rather than counted in Java. Reading the window
    // to count it would pull every row into memory to produce nine numbers.
    // ----------------------------------------------------------------------- //

    @Query("""
            SELECT new io.github.la3679.sentinelflow.api.persistence.repository.AlertRepository$StatusCount(
                       a.status, count(a))
              FROM Alert a
             WHERE a.createdAt >= :from AND a.createdAt < :to
             GROUP BY a.status
            """)
    List<StatusCount> countByStatus(@Param("from") Instant from, @Param("to") Instant to);

    @Query("""
            SELECT new io.github.la3679.sentinelflow.api.persistence.repository.AlertRepository$PriorityCount(
                       a.priority, count(a))
              FROM Alert a
             WHERE a.createdAt >= :from AND a.createdAt < :to
             GROUP BY a.priority
            """)
    List<PriorityCount> countByPriority(@Param("from") Instant from, @Param("to") Instant to);

    @Query("""
            SELECT new io.github.la3679.sentinelflow.api.persistence.repository.AlertRepository$BandCount(
                       a.riskBand, count(a))
              FROM Alert a
             WHERE a.createdAt >= :from AND a.createdAt < :to
             GROUP BY a.riskBand
            """)
    List<BandCount> countByBand(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * How many alerts in the window are still open.
     *
     * <p>From {@code closedAt} rather than from a list of statuses. Which statuses mean open is a
     * fact about the state machine, and a second copy of it here would be a second place to change
     * when a status is added — and the copy that was forgotten would produce a report that is wrong
     * rather than one that fails.
     */
    @Query("SELECT count(a) FROM Alert a WHERE a.createdAt >= :from AND a.createdAt < :to AND a.closedAt IS NULL")
    long countOpen(@Param("from") Instant from, @Param("to") Instant to);

    @Query("SELECT count(a) FROM Alert a WHERE a.createdAt >= :from AND a.createdAt < :to")
    long countInWindow(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * The window itself, oldest first, bounded by the caller's limit.
     *
     * <p>Oldest first because an export is read as a chronology rather than as a queue. The limit is
     * not a paging cursor: the caller has already refused a window larger than it, so this reads a
     * window it knows fits.
     */
    @Query("""
            SELECT a FROM Alert a
             WHERE a.createdAt >= :from AND a.createdAt < :to
             ORDER BY a.createdAt ASC, a.id ASC
            """)
    List<Alert> findWindow(@Param("from") Instant from, @Param("to") Instant to, Limit limit);

    /** One status and how many alerts in the window hold it. */
    record StatusCount(AlertStatus status, long total) {}

    /** One priority and how many alerts in the window hold it. */
    record PriorityCount(AlertPriority priority, long total) {}

    /** One band and how many alerts in the window carry it. */
    record BandCount(RiskBand band, long total) {}
}
