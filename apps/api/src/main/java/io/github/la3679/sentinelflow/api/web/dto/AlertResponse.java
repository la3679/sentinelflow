/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.la3679.sentinelflow.api.alert.AlertTransitions;
import io.github.la3679.sentinelflow.api.domain.ActorRole;
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
 *
 * <h2>{@code legalTargets} depends on who is reading</h2>
 *
 * Every other field here is a property of the alert. This one is a property of the alert
 * <em>and the caller</em>: it names the moves this reader may make, so an analyst does not see the
 * administrative close and an auditor sees nothing at all. That is deliberate — a console that
 * subtracted the administrator's move itself would hold a second copy of a rule that lives in
 * {@code AlertTransitions}, and the copy would be what went stale.
 *
 * <p>It is on the queue rows as well as the detail read. One schema, one shape: a list whose
 * elements were a different type from the single read would be a special case every client has to
 * know about, and the value is computed from an in-memory map with no query behind it.
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
        List<String> legalTargets,
        Instant createdAt,
        Instant updatedAt,
        Instant closedAt) {

    /**
     * @param role the capacity the reader holds, which decides {@code legalTargets}
     */
    public static AlertResponse of(Alert alert, ActorRole role) {
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
                AlertTransitions.namesOf(AlertTransitions.legalTargetsFor(alert.getStatus(), role)),
                alert.getCreatedAt(),
                alert.getUpdatedAt(),
                alert.getClosedAt());
    }
}
