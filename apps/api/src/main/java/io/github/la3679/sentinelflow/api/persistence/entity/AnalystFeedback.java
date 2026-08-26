/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;

import io.github.la3679.sentinelflow.api.domain.FeedbackLabel;

/**
 * An analyst's verdict on an assessment: the label source for any future supervised training.
 *
 * <p>One analyst gives one label per assessment, enforced by a unique constraint. Changing your
 * mind updates the row rather than adding a second, contradictory training label - two opposite
 * labels from the same person about the same decision would poison a training set quietly, and
 * there is no principled way to choose between them afterwards.
 *
 * <p>{@code alertId} is nullable because an assessment can be reviewed without an alert ever having
 * been raised for it, which is precisely the feedback a false-negative review produces.
 */
@Entity
@Table(name = "analyst_feedback")
public class AnalystFeedback extends AbstractEntity {

    @Column(name = "assessment_id", nullable = false, updatable = false)
    private UUID assessmentId;

    @Column(name = "alert_id", updatable = false)
    private UUID alertId;

    @Column(name = "actor_id", nullable = false, updatable = false)
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "label", nullable = false, length = 24)
    private FeedbackLabel label;

    @Column(name = "reason", length = 1000)
    private String reason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AnalystFeedback() {}

    public AnalystFeedback(UUID assessmentId, UUID alertId, UUID actorId, FeedbackLabel label, String reason) {
        this.assessmentId = assessmentId;
        this.alertId = alertId;
        this.actorId = actorId;
        this.label = label;
        this.reason = reason;
    }

    /** Revising a verdict replaces the label rather than adding a second one. */
    public void revise(FeedbackLabel label, String reason) {
        this.label = label;
        this.reason = reason;
    }

    public UUID getAssessmentId() {
        return assessmentId;
    }

    public UUID getAlertId() {
        return alertId;
    }

    public UUID getActorId() {
        return actorId;
    }

    public FeedbackLabel getLabel() {
        return label;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
