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
 *
 * <h2>{@code assignee} is resolved here rather than by the reader</h2>
 *
 * {@code assigneeId} stays, because a client that already stores an identifier should not have to
 * change to keep working, and because it is the value the assignment endpoint takes back. Beside it
 * is the person that identifier names, so a queue row can render somebody without every client
 * loading the operator directory first (ADR-0019).
 *
 * <p><strong>Both are null together and neither is null alone</strong>, with one exception worth
 * knowing: an identifier that resolves to no row publishes an id with a null assignee. That is not a
 * state the schema can currently reach - {@code alerts.assignee_id} references {@code users} - and
 * it is representable on purpose, because the honest answer to "who is this" is nothing rather than
 * a placeholder.
 */
public record AlertResponse(
        UUID alertId,
        String alertReference,
        UUID transactionId,
        UUID assessmentId,
        AlertStatus status,
        AlertPriority priority,
        UUID assigneeId,
        AlertAssigneeResponse assignee,
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
     * @param assignee the person {@code assigneeId} names, or null when the alert is unassigned.
     *     Passed in rather than looked up here, because a DTO that queried would be one query per
     *     row of a page - the N+1 {@code OperatorDirectory.resolve} exists to avoid.
     */
    public static AlertResponse of(Alert alert, ActorRole role, AlertAssigneeResponse assignee) {
        return new AlertResponse(
                alert.getId(),
                alert.getAlertReference(),
                alert.getTransactionId(),
                alert.getAssessmentId(),
                alert.getStatus(),
                alert.getPriority(),
                alert.getAssigneeId(),
                assignee,
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
