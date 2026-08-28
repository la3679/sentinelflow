/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.messaging.payload;

import java.time.Instant;
import java.util.UUID;

import io.github.la3679.sentinelflow.api.domain.Actor;
import io.github.la3679.sentinelflow.api.domain.ActorRole;
import io.github.la3679.sentinelflow.api.domain.AlertChangeType;
import io.github.la3679.sentinelflow.api.domain.AlertPriority;
import io.github.la3679.sentinelflow.api.domain.AlertStatus;
import io.github.la3679.sentinelflow.api.persistence.entity.Alert;

/**
 * The {@code alert.updated} payload, v1.
 *
 * <p>Field-for-field with {@code contracts/schemas/alert-updated.v1.json}, which declares
 * {@code additionalProperties: false}. {@code AlertUpdatedContractIT} asserts the two agree in both
 * directions.
 *
 * <p><strong>Both the previous and the new state travel on every event.</strong> A consumer that
 * only ever saw the new one could not reconstruct the path an alert took, and the path is what an
 * audit asks about. That is also why an assignment carries a status that did not change and a
 * transition carries an assignee that did not: every change type fills every field, and
 * {@code changeType} rather than a diff is what says which kind of change this was.
 *
 * <p><strong>{@code version} is the alert's version <em>after</em> the change</strong>, and the
 * schema requires it to be at least 1. An alert is raised at version 0 and this event only ever
 * describes a change to one, so by the time it is published Hibernate has incremented at least
 * once. A consumer can therefore detect a gap, and discard a late duplicate on ordering rather than
 * only on {@code eventId}.
 *
 * <p><strong>The version is read after a flush, not guessed at.</strong> {@code @Version} is
 * incremented by the persistence provider, so the value this payload needs does not exist until the
 * update has been written. The publisher flushes first; adding one to the version in memory would
 * be a second implementation of Hibernate's counter, correct until the day something else touched
 * the row in the same transaction.
 *
 * <p><strong>Keyed by the alert's identifier</strong> (ADR-0006 §3), like {@code alert.created}: one
 * alert's changes are ordered against each other and against nothing else.
 */
public record AlertUpdatedPayload(
        UUID alertId,
        String alertReference,
        AlertChangeType changeType,
        AlertStatus previousStatus,
        AlertStatus status,
        UUID previousAssignee,
        UUID assignee,
        AlertPriority priority,
        UUID actorId,
        ActorRole actorRole,
        long version,
        Instant occurredAt) {

    /**
     * Reads the payload off the alert as it now stands, plus what it was before.
     *
     * @param alert the alert after the change, already flushed so its version is the new one
     * @param changeType which kind of change this was
     * @param previousStatus the status before the change, which equals {@code alert.getStatus()}
     *     for every change type except a transition
     * @param previousAssignee who held the alert before, or null when it was unassigned
     * @param actor who made the change, and in what capacity
     * @param occurredAt the moment the change was made, taken from the caller so the audit row and
     *     the event agree
     */
    public static AlertUpdatedPayload of(
            Alert alert,
            AlertChangeType changeType,
            AlertStatus previousStatus,
            UUID previousAssignee,
            Actor actor,
            Instant occurredAt) {
        return new AlertUpdatedPayload(
                alert.getId(),
                alert.getAlertReference(),
                changeType,
                previousStatus,
                alert.getStatus(),
                previousAssignee,
                alert.getAssigneeId(),
                alert.getPriority(),
                actor.userId(),
                actor.role(),
                alert.getVersion(),
                occurredAt);
    }
}
