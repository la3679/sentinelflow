/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.la3679.sentinelflow.api.domain.ReasonCode;
import io.github.la3679.sentinelflow.api.domain.RiskBand;
import io.github.la3679.sentinelflow.api.persistence.entity.RiskAssessment;

/**
 * The decision behind a transaction, as the API describes it.
 *
 * <p>Field-for-field with the {@code RiskAssessment} schema in {@code contracts/openapi/}, which
 * declares {@code additionalProperties: false}.
 *
 * <h2>Every version that contributed is on the wire</h2>
 *
 * A score an analyst has to defend months later is only defensible if what produced it can be named:
 * the model, the features it was computed from, the ruleset, and the policy that combined them. Four
 * fields rather than one because they move independently — a ruleset change with no retraining is
 * ordinary.
 *
 * <p><strong>{@code degraded} is explicit rather than inferred from a null {@code modelScore}.</strong>
 * A reader working it out for themselves is a rule in every client, and the two could disagree the
 * day a model legitimately declined to score.
 *
 * <p>{@code alertRaised} and {@code assessmentVersion} are deliberately absent: the first is
 * answered by whether an alert exists, and the second is internal bookkeeping for rescoring that no
 * screen acts on.
 */
public record RiskAssessmentResponse(
        UUID assessmentId,
        UUID transactionId,
        BigDecimal ruleScore,
        BigDecimal modelScore,
        BigDecimal finalScore,
        RiskBand riskBand,
        boolean degraded,
        String modelVersion,
        String featureVersion,
        String rulesetVersion,
        String policyVersion,
        List<ReasonCode> reasonCodes,
        int scoringLatencyMs,
        Instant assessedAt) {

    public static RiskAssessmentResponse of(RiskAssessment assessment) {
        return new RiskAssessmentResponse(
                assessment.getId(),
                assessment.getTransactionId(),
                assessment.getRuleScore(),
                assessment.getModelScore(),
                assessment.getFinalScore(),
                assessment.getRiskBand(),
                assessment.isDegraded(),
                assessment.getModelVersion(),
                assessment.getFeatureVersion(),
                assessment.getRulesetVersion(),
                assessment.getPolicyVersion(),
                assessment.getReasonCodes(),
                assessment.getScoringLatencyMs(),
                assessment.getAssessedAt());
    }
}
