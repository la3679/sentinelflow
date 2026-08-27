/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.messaging.payload;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.la3679.sentinelflow.api.domain.ReasonCode;
import io.github.la3679.sentinelflow.api.domain.RiskBand;
import io.github.la3679.sentinelflow.api.persistence.entity.RiskAssessment;

/**
 * The {@code risk.assessed} payload, v1.
 *
 * <p>Field-for-field with {@code contracts/schemas/risk-assessed.v1.json}, which declares
 * {@code additionalProperties: false} — so an extra field here is not an addition, it is a message
 * every conforming consumer must reject. {@code RiskAssessedContractIT} asserts the two agree in
 * both directions, because a schema file is data as far as the compiler is concerned.
 *
 * <p><strong>Every version that contributed is carried, and two of them are nullable.</strong>
 * {@code modelVersion} and {@code featureVersion} are null on a degraded assessment because no
 * scoring call happened, and {@code modelScore} is null for the same reason. Null is the expected
 * outcome rather than an error, and {@code degraded} states it explicitly so a consumer filtering
 * for degraded assessments does not have to encode the inference {@code modelScore == null}.
 *
 * <p><strong>Scores are JSON numbers here and money is not.</strong> That is not an inconsistency:
 * ADR-0007 keeps money out of JSON numbers because a rounding difference in the last place is a
 * different amount of money, and a score is a computed measure where it changes nothing a decision
 * depends on. {@code common.v1.json}'s {@code score} says so.
 *
 * <p><strong>{@code accountId} is on the payload and is the partition key.</strong> The same
 * account keys {@code transaction.created.v1}, so an account's transactions and their assessments
 * stay ordered relative to one another rather than racing across partitions.
 */
public record RiskAssessedPayload(
        UUID assessmentId,
        UUID transactionId,
        UUID accountId,
        BigDecimal ruleScore,
        BigDecimal modelScore,
        BigDecimal finalScore,
        RiskBand riskBand,
        boolean degraded,
        String modelVersion,
        String featureVersion,
        String policyVersion,
        List<ReasonCode> reasonCodes,
        int scoringLatencyMs,
        boolean alertRaised,
        Instant assessedAt) {

    /**
     * Reads the payload off the persisted assessment rather than off the values that produced it.
     *
     * <p>Deliberate: the row is what an analyst will be shown and what an audit will read, so an
     * event describing anything else would be a second version of the same decision. It also means
     * the database's own {@code CHECK} constraints have already rejected the shapes this payload
     * must never carry — a degraded assessment with a model score, a score outside the scale — before
     * the payload can be built from one.
     *
     * @param accountId the transaction's account. Not on the assessment, which is about a
     *     transaction; the caller has the transaction in hand and passes it rather than this
     *     re-reading the row to find out.
     */
    public static RiskAssessedPayload of(RiskAssessment assessment, UUID accountId) {
        return new RiskAssessedPayload(
                assessment.getId(),
                assessment.getTransactionId(),
                accountId,
                assessment.getRuleScore(),
                assessment.getModelScore(),
                assessment.getFinalScore(),
                assessment.getRiskBand(),
                assessment.isDegraded(),
                assessment.getModelVersion(),
                assessment.getFeatureVersion(),
                assessment.getPolicyVersion(),
                assessment.getReasonCodes(),
                assessment.getScoringLatencyMs(),
                assessment.isAlertRaised(),
                assessment.getAssessedAt());
    }
}
