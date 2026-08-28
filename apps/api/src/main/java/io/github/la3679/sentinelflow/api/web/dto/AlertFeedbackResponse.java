/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web.dto;

import java.time.Instant;
import java.util.UUID;

import io.github.la3679.sentinelflow.api.domain.FeedbackLabel;
import io.github.la3679.sentinelflow.api.persistence.entity.AnalystFeedback;

/**
 * One analyst's verdict, as the API describes it.
 *
 * <p>{@code assessmentId} rather than only the alert, because the label is about the decision and
 * not about the queue item: rescoring writes a new assessment, and a label attached to the alert
 * would silently follow a decision it was never given about.
 */
public record AlertFeedbackResponse(
        UUID feedbackId,
        UUID assessmentId,
        UUID alertId,
        UUID actorId,
        FeedbackLabel label,
        String reason,
        Instant createdAt) {

    public static AlertFeedbackResponse of(AnalystFeedback feedback) {
        return new AlertFeedbackResponse(
                feedback.getId(),
                feedback.getAssessmentId(),
                feedback.getAlertId(),
                feedback.getActorId(),
                feedback.getLabel(),
                feedback.getReason(),
                feedback.getCreatedAt());
    }
}
