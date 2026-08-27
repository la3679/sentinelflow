/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import io.github.la3679.sentinelflow.api.persistence.entity.RiskAssessment;

/**
 * Risk assessments.
 *
 * <p><strong>No {@code findByTransactionId} returning one row.</strong> A transaction can carry
 * more than one assessment — {@code assessment_version} is part of the uniqueness so a rescoring
 * under a new policy keeps the decision that was actually acted on — and a finder that returned
 * "the" assessment would work for as long as nothing had ever been rescored and then quietly return
 * whichever row the plan produced first. The current one is the highest version, and asking for it
 * says so.
 */
public interface RiskAssessmentRepository extends JpaRepository<RiskAssessment, UUID> {

    /**
     * The assessment a console or an audit means when it says "the assessment for this
     * transaction": the most recent one, by version rather than by time.
     *
     * <p>Ordered by the second half of {@code risk_assessments_transaction_idx}, which is
     * {@code (transaction_id, assessment_version DESC)} and therefore serves both the filter and
     * the ordering without a sort.
     */
    Optional<RiskAssessment> findFirstByTransactionIdOrderByAssessmentVersionDesc(UUID transactionId);

    /** Whether this transaction has been assessed at all. Used by the workflow's own tests. */
    boolean existsByTransactionId(UUID transactionId);
}
