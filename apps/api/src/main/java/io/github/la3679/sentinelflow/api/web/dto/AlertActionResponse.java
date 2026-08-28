/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web.dto;

import java.time.Instant;
import java.util.UUID;

import io.github.la3679.sentinelflow.api.domain.ActorRole;
import io.github.la3679.sentinelflow.api.domain.AlertActionType;
import io.github.la3679.sentinelflow.api.domain.AlertStatus;
import io.github.la3679.sentinelflow.api.persistence.entity.AlertAction;

/**
 * One entry in an alert's history, as the API describes one.
 *
 * <p>Field-for-field with the {@code AlertAction} schema. {@code actionId} rather than {@code id},
 * because the contract says so and because a page of objects that all call their key {@code id}
 * reads badly on the client side.
 *
 * <p><strong>The actor is an identifier and a role, never a name.</strong> Resolving it to a display
 * name is the console's to do if it wants to, from the directory it already needs; putting it here
 * would mean every history page joined a table to say something the page may not even show.
 */
public record AlertActionResponse(
        UUID actionId,
        UUID alertId,
        UUID actorId,
        ActorRole actorRole,
        AlertActionType actionType,
        AlertStatus previousStatus,
        AlertStatus newStatus,
        String note,
        UUID correlationId,
        Instant occurredAt) {

    public static AlertActionResponse of(AlertAction action) {
        return new AlertActionResponse(
                action.getId(),
                action.getAlertId(),
                action.getActorId(),
                action.getActorRole(),
                action.getActionType(),
                action.getPreviousStatus(),
                action.getNewStatus(),
                action.getNote(),
                action.getCorrelationId(),
                action.getOccurredAt());
    }
}
