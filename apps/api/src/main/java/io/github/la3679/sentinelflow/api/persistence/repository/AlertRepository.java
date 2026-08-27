/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
}
