/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;

import io.github.la3679.sentinelflow.api.domain.ActorRole;
import io.github.la3679.sentinelflow.api.domain.AlertActionType;
import io.github.la3679.sentinelflow.api.domain.AlertStatus;

/**
 * One entry in an alert's history. Append-only: never updated, never deleted.
 *
 * <p><strong>Every action has an actor.</strong> {@code actorId} is not nullable, and an automated
 * action is attributed to the system principal V1 inserts rather than to null. An unattributable
 * change to a reviewed decision is exactly what an audit trail exists to make impossible.
 *
 * <p>There are no setters. A history entry that can be edited is not a history.
 */
@Entity
@Table(name = "alert_actions")
public class AlertAction extends AbstractEntity {

    @Column(name = "alert_id", nullable = false, updatable = false)
    private UUID alertId;

    @Column(name = "actor_id", nullable = false, updatable = false)
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_role", nullable = false, length = 16, updatable = false)
    private ActorRole actorRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 24, updatable = false)
    private AlertActionType actionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 32, updatable = false)
    private AlertStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", length = 32, updatable = false)
    private AlertStatus newStatus;

    @Column(name = "note", length = 2000, updatable = false)
    private String note;

    @Column(name = "correlation_id", nullable = false, updatable = false)
    private UUID correlationId;

    @CreationTimestamp
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected AlertAction() {}

    private AlertAction(
            UUID alertId, UUID actorId, ActorRole actorRole, AlertActionType actionType, UUID correlationId) {
        this.alertId = alertId;
        this.actorId = actorId;
        this.actorRole = actorRole;
        this.actionType = actionType;
        this.correlationId = correlationId;
    }

    /**
     * A status change. Both ends are required and must differ, which the database also enforces: a
     * transition row that does not say what it moved from cannot answer the question audits ask.
     */
    public static AlertAction transition(
            UUID alertId,
            UUID actorId,
            ActorRole actorRole,
            AlertStatus previousStatus,
            AlertStatus newStatus,
            String note,
            UUID correlationId) {
        AlertAction action = new AlertAction(alertId, actorId, actorRole, AlertActionType.TRANSITIONED, correlationId);
        action.previousStatus = previousStatus;
        action.newStatus = newStatus;
        action.note = note;
        return action;
    }

    /** Anything that is not a status change: creation, assignment, a note, a priority change. */
    public static AlertAction of(
            UUID alertId,
            UUID actorId,
            ActorRole actorRole,
            AlertActionType actionType,
            String note,
            UUID correlationId) {
        AlertAction action = new AlertAction(alertId, actorId, actorRole, actionType, correlationId);
        action.note = note;
        return action;
    }

    public UUID getAlertId() {
        return alertId;
    }

    public UUID getActorId() {
        return actorId;
    }

    public ActorRole getActorRole() {
        return actorRole;
    }

    public AlertActionType getActionType() {
        return actionType;
    }

    public AlertStatus getPreviousStatus() {
        return previousStatus;
    }

    public AlertStatus getNewStatus() {
        return newStatus;
    }

    public String getNote() {
        return note;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
