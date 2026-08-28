/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import io.github.la3679.sentinelflow.api.persistence.entity.AnalystFeedback;

/**
 * Analyst dispositions: the label source a future supervised model trains on.
 *
 * <p>{@code analyst_feedback_unique} makes the lookup below return at most one row, which is what
 * turns "record my verdict" into an update rather than a second, contradictory label from the same
 * person about the same decision.
 */
public interface AnalystFeedbackRepository extends JpaRepository<AnalystFeedback, UUID> {

    /** This analyst's verdict on this assessment, if they have given one. */
    Optional<AnalystFeedback> findByAssessmentIdAndActorId(UUID assessmentId, UUID actorId);
}
