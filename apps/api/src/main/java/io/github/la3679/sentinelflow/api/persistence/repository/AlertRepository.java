/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.github.la3679.sentinelflow.api.domain.AlertPriority;
import io.github.la3679.sentinelflow.api.domain.AlertStatus;
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
}
