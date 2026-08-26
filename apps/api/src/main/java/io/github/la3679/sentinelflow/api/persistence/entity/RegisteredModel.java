/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import io.github.la3679.sentinelflow.api.domain.ModelStatus;

/**
 * One trained model, and the evidence that makes its scores reproducible.
 *
 * <p>A score without the exact configuration that produced it cannot be reproduced, defended, or
 * compared with another score. The version strings on {@link RiskAssessment} mean something only
 * because they name a row here, with a training-data fingerprint and an artifact checksum, rather
 * than being free text nobody can resolve later.
 *
 * <p><strong>At most one model is active</strong>, enforced by a partial unique index rather than
 * by whichever code path last performed a promotion. Two active models do not make a state to
 * detect later; they make "which model scored this" ambiguous for every assessment written while it
 * lasted.
 *
 * <p><strong>{@code metrics} are recorded from an evaluation run, never estimated.</strong> The
 * keys vary as the evaluation does and nothing queries an individual metric, so JSONB is the honest
 * representation rather than a column per metric that goes stale.
 */
@Entity
@Table(name = "model_registry")
public class RegisteredModel extends AbstractEntity {

    @Column(name = "model_version", nullable = false, length = 32, updatable = false)
    private String modelVersion;

    @Column(name = "feature_version", nullable = false, length = 32, updatable = false)
    private String featureVersion;

    @Column(name = "training_data_fingerprint", nullable = false, length = 64, updatable = false)
    private String trainingDataFingerprint;

    @Column(name = "artifact_checksum", nullable = false, length = 64, updatable = false)
    private String artifactChecksum;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metrics", nullable = false, updatable = false)
    private String metrics;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ModelStatus status;

    @Column(name = "trained_at", nullable = false, updatable = false)
    private Instant trainedAt;

    @Column(name = "promoted_at")
    private Instant promotedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RegisteredModel() {}

    /** A newly trained model. Every model enters the registry as a candidate; nothing is born active. */
    public RegisteredModel(
            String modelVersion,
            String featureVersion,
            String trainingDataFingerprint,
            String artifactChecksum,
            String metrics,
            Instant trainedAt) {
        this.modelVersion = modelVersion;
        this.featureVersion = featureVersion;
        this.trainingDataFingerprint = trainingDataFingerprint;
        this.artifactChecksum = artifactChecksum;
        this.metrics = metrics;
        this.trainedAt = trainedAt;
        this.status = ModelStatus.CANDIDATE;
    }

    /**
     * Promotes this model to active and records when. Promotion is what makes a model active, so an
     * active model that was never promoted is a row that lost its own history - the database
     * refuses one, and this is the only path that sets the status.
     */
    public void promote(Instant at) {
        this.status = ModelStatus.ACTIVE;
        this.promotedAt = at;
    }

    public void retire() {
        this.status = ModelStatus.RETIRED;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public String getFeatureVersion() {
        return featureVersion;
    }

    public String getTrainingDataFingerprint() {
        return trainingDataFingerprint;
    }

    public String getArtifactChecksum() {
        return artifactChecksum;
    }

    public String getMetrics() {
        return metrics;
    }

    public ModelStatus getStatus() {
        return status;
    }

    public Instant getTrainedAt() {
        return trainedAt;
    }

    public Instant getPromotedAt() {
        return promotedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
