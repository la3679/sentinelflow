/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import io.github.la3679.sentinelflow.api.domain.AlertPriority;
import io.github.la3679.sentinelflow.api.domain.AlertStatus;
import io.github.la3679.sentinelflow.api.domain.RiskBand;
import io.github.la3679.sentinelflow.api.persistence.entity.Alert;

/**
 * An alert as the API describes one.
 *
 * <p>Field-for-field with the {@code Alert} schema in {@code contracts/openapi/}, which declares
 * {@code additionalProperties: false}. Built from the entity and never the entity itself: exposing a
 * JPA entity leaks the schema into the contract and turns a lazy association into a serialization
 * bug.
 *
 * <p><strong>{@code version} is on the wire because a client has to send it back.</strong> It is an
 * opaque concurrency token — compared for equality, never read for meaning — and a client that
 * cannot see it cannot make a safe mutation.
 */
public record AlertResponse(
        UUID alertId,
        String alertReference,
        UUID transactionId,
        UUID assessmentId,
        AlertStatus status,
        AlertPriority priority,
        UUID assigneeId,
        String summary,
        RiskBand riskBand,
        BigDecimal finalScore,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant closedAt) {

    public static AlertResponse of(Alert alert) {
        return new AlertResponse(
                alert.getId(),
                alert.getAlertReference(),
                alert.getTransactionId(),
                alert.getAssessmentId(),
                alert.getStatus(),
                alert.getPriority(),
                alert.getAssigneeId(),
                alert.getSummary(),
                alert.getRiskBand(),
                alert.getFinalScore(),
                alert.getVersion(),
                alert.getCreatedAt(),
                alert.getUpdatedAt(),
                alert.getClosedAt());
    }
}
