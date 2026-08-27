/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import io.github.la3679.sentinelflow.api.domain.ReasonCode;
import io.github.la3679.sentinelflow.api.domain.RiskBand;

/**
 * What scoring decided about one transaction, and everything needed to defend that decision later.
 *
 * <p><strong>Rescoring adds a row.</strong> {@code assessmentVersion} is part of the uniqueness, so
 * a transaction re-run under a new policy keeps its old assessment: that is the decision that was
 * acted on, and an audit that cannot see it is not an audit.
 *
 * <p><strong>Degraded is a complete state, not a flag.</strong> A degraded assessment was produced
 * without the model, so it has no model score, no model version, no feature version and no scoring
 * latency, because no scoring call happened. The database enforces both shapes; the two factory
 * methods below are the only way to build either, so a partially-failed scoring path cannot
 * construct the third shape that does not exist.
 */
@Entity
@Table(name = "risk_assessments")
public class RiskAssessment extends AbstractEntity {

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "assessment_version", nullable = false, updatable = false)
    private int assessmentVersion;

    @Column(name = "rule_score", nullable = false, precision = 5, scale = 2, updatable = false)
    private BigDecimal ruleScore;

    @Column(name = "model_score", precision = 5, scale = 2, updatable = false)
    private BigDecimal modelScore;

    @Column(name = "final_score", nullable = false, precision = 5, scale = 2, updatable = false)
    private BigDecimal finalScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_band", nullable = false, length = 16, updatable = false)
    private RiskBand riskBand;

    @Column(name = "degraded", nullable = false, updatable = false)
    private boolean degraded;

    @Column(name = "model_version", length = 32, updatable = false)
    private String modelVersion;

    @Column(name = "feature_version", length = 32, updatable = false)
    private String featureVersion;

    @Column(name = "policy_version", nullable = false, length = 32, updatable = false)
    private String policyVersion;

    /**
     * The ruleset that produced {@link #ruleScore} and every {@code RULE} reason.
     *
     * <p><strong>Not nullable, including on a degraded assessment.</strong> The other two component
     * versions describe a scoring call that may not have happened; this one describes the half that
     * always runs, in this process, in this transaction. An assessment naming a model and a policy
     * but not the weights that set its floor cannot be reproduced. Added in V8, because nothing had
     * written this table until the assessment workflow and so nothing had been forced to put every
     * version it depends on somewhere.
     */
    @Column(name = "ruleset_version", nullable = false, length = 32, updatable = false)
    private String rulesetVersion;

    /**
     * The one place JSONB is the honest representation: a reason list is genuinely variable in
     * length, and nothing queries or constrains an individual member. The domain does not live in
     * here - every other field on this entity is a column.
     *
     * <p><strong>Objects, not strings.</strong> This was a list of bare codes from Phase 2 until the
     * assessment workflow landed, while {@code contracts/schemas/common.v1.json} had always described
     * a code, a description, a contribution and a source. Nothing noticed because nothing wrote the
     * column; the first write was also the first time the two had to agree. See
     * {@link ReasonCode}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reason_codes", nullable = false, updatable = false)
    private List<ReasonCode> reasonCodes;

    @Column(name = "scoring_latency_ms", nullable = false, updatable = false)
    private int scoringLatencyMs;

    @Column(name = "alert_raised", nullable = false, updatable = false)
    private boolean alertRaised;

    @Column(name = "assessed_at", nullable = false, updatable = false)
    private Instant assessedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RiskAssessment() {}

    private RiskAssessment(UUID transactionId, int assessmentVersion, String rulesetVersion, String policyVersion) {
        this.transactionId = transactionId;
        this.assessmentVersion = assessmentVersion;
        this.rulesetVersion = rulesetVersion;
        this.policyVersion = policyVersion;
    }

    /** An assessment the model contributed to. */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static RiskAssessment scored(
            UUID transactionId,
            int assessmentVersion,
            BigDecimal ruleScore,
            BigDecimal modelScore,
            BigDecimal finalScore,
            RiskBand riskBand,
            String modelVersion,
            String featureVersion,
            String rulesetVersion,
            String policyVersion,
            List<ReasonCode> reasonCodes,
            int scoringLatencyMs,
            boolean alertRaised,
            Instant assessedAt) {
        RiskAssessment assessment = new RiskAssessment(transactionId, assessmentVersion, rulesetVersion, policyVersion);
        assessment.ruleScore = ruleScore;
        assessment.modelScore = modelScore;
        assessment.finalScore = finalScore;
        assessment.riskBand = riskBand;
        assessment.degraded = false;
        assessment.modelVersion = modelVersion;
        assessment.featureVersion = featureVersion;
        assessment.reasonCodes = List.copyOf(reasonCodes);
        assessment.scoringLatencyMs = scoringLatencyMs;
        assessment.alertRaised = alertRaised;
        assessment.assessedAt = assessedAt;
        return assessment;
    }

    /**
     * An assessment produced from rules alone, because scoring was unreachable. Every model-derived
     * field is absent rather than defaulted: a zero model score is a claim about the transaction,
     * and no such claim was made.
     */
    public static RiskAssessment degraded(
            UUID transactionId,
            int assessmentVersion,
            BigDecimal ruleScore,
            BigDecimal finalScore,
            RiskBand riskBand,
            String rulesetVersion,
            String policyVersion,
            List<ReasonCode> reasonCodes,
            boolean alertRaised,
            Instant assessedAt) {
        RiskAssessment assessment = new RiskAssessment(transactionId, assessmentVersion, rulesetVersion, policyVersion);
        assessment.ruleScore = ruleScore;
        assessment.finalScore = finalScore;
        assessment.riskBand = riskBand;
        assessment.degraded = true;
        assessment.reasonCodes = List.copyOf(reasonCodes);
        assessment.scoringLatencyMs = 0;
        assessment.alertRaised = alertRaised;
        assessment.assessedAt = assessedAt;
        return assessment;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public int getAssessmentVersion() {
        return assessmentVersion;
    }

    public BigDecimal getRuleScore() {
        return ruleScore;
    }

    public BigDecimal getModelScore() {
        return modelScore;
    }

    public BigDecimal getFinalScore() {
        return finalScore;
    }

    public RiskBand getRiskBand() {
        return riskBand;
    }

    public boolean isDegraded() {
        return degraded;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public String getFeatureVersion() {
        return featureVersion;
    }

    public String getRulesetVersion() {
        return rulesetVersion;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public List<ReasonCode> getReasonCodes() {
        return List.copyOf(reasonCodes);
    }

    public int getScoringLatencyMs() {
        return scoringLatencyMs;
    }

    public boolean isAlertRaised() {
        return alertRaised;
    }

    public Instant getAssessedAt() {
        return assessedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
