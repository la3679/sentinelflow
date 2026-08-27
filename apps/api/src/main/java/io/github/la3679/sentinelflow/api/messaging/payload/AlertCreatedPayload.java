/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.messaging.payload;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.la3679.sentinelflow.api.domain.AlertPriority;
import io.github.la3679.sentinelflow.api.domain.AlertStatus;
import io.github.la3679.sentinelflow.api.domain.ReasonCode;
import io.github.la3679.sentinelflow.api.domain.RiskBand;
import io.github.la3679.sentinelflow.api.persistence.entity.Alert;

/**
 * The {@code alert.created} payload, v1.
 *
 * <p>Field-for-field with {@code contracts/schemas/alert-created.v1.json}, which declares
 * {@code additionalProperties: false} — so an extra field here is a message every conforming
 * consumer must reject. {@code AlertCreatedContractIT} asserts the two agree in both directions.
 *
 * <p><strong>Keyed by the alert, not by the account</strong> (ADR-0006 §3). An alert's own
 * transitions must be ordered with respect to each other and have no ordering relationship with any
 * other alert, which is a different guarantee from the per-account ordering the transaction and
 * assessment topics need. {@code accountId} is on the payload for correlation and is deliberately
 * not the key.
 *
 * <p><strong>{@code status} is always {@code NEW}</strong>, and the schema constrains it with
 * {@code const} rather than merely documenting it — a producer bug cannot emit a created event in a
 * later state.
 *
 * <p><strong>{@code topReasonCode} is duplicated from the assessment on purpose.</strong> A queue
 * view renders one row per alert and would otherwise have to join to say anything about why the
 * alert exists. The duplication is safe because an assessment is immutable: rescoring writes a new
 * assessment rather than editing this one, so the copy cannot drift from its source.
 */
public record AlertCreatedPayload(
        UUID alertId,
        String alertReference,
        UUID assessmentId,
        UUID transactionId,
        UUID accountId,
        AlertStatus status,
        AlertPriority priority,
        RiskBand riskBand,
        BigDecimal finalScore,
        String summary,
        ReasonCode topReasonCode,
        Instant createdAt) {

    /**
     * Reads the payload off the persisted alert, with the one field the alert does not carry.
     *
     * @param accountId the transaction's account, for correlation. Not on {@link Alert}, which is
     *     about a transaction; the caller has it and passes it rather than this re-reading a row.
     * @param topReasonCode the first reason on the assessment. See {@link #firstReasonOf}.
     * @param createdAt the moment the alert was raised. Taken from the caller rather than from the
     *     entity, because {@code created_at} is a database default that is not populated until the
     *     row is flushed — and this payload is built before that, in the same transaction.
     */
    public static AlertCreatedPayload of(Alert alert, UUID accountId, ReasonCode topReasonCode, Instant createdAt) {
        return new AlertCreatedPayload(
                alert.getId(),
                alert.getAlertReference(),
                alert.getAssessmentId(),
                alert.getTransactionId(),
                accountId,
                alert.getStatus(),
                alert.getPriority(),
                alert.getRiskBand(),
                alert.getFinalScore(),
                alert.getSummary(),
                topReasonCode,
                createdAt);
    }

    /**
     * The reason an alert leads with: the first in the assessment's own ordering.
     *
     * <p><strong>Not "the largest contributor", which is not a well-defined thing here.</strong> The
     * assessment orders its reasons rules-first, each group by descending contribution, because a
     * rule's weight on the 0-to-100 scale and a model's log-odds decomposition are not comparable
     * magnitudes. So this is the largest rule contributor when any rule fired, and the model's
     * largest attribution otherwise. That is a rule an analyst can be told in one sentence, where
     * "largest across both" would be a number comparison that means nothing.
     *
     * @throws IllegalArgumentException if the list is empty, which
     *     {@code risk_assessments_reason_codes_shape} already makes impossible — an assessment
     *     always carries at least {@code NO_INDICATORS}.
     */
    public static ReasonCode firstReasonOf(List<ReasonCode> reasonCodes) {
        if (reasonCodes.isEmpty()) {
            throw new IllegalArgumentException("An assessment with no reason cannot open an alert, and the column's "
                    + "CHECK makes one impossible. Reaching here means the row was not read from the database.");
        }
        return reasonCodes.get(0);
    }
}
