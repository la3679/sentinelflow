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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import io.github.la3679.sentinelflow.api.domain.ActorType;

/**
 * One append-only record of something that happened, and who caused it.
 *
 * <p><strong>Sanitised metadata only.</strong> {@code beforeState} and {@code afterState} carry the
 * fields a reviewer needs to understand a change - never a credential, never a raw payload, never
 * personal data. Reading "before and after" as "the whole row" is how an audit log becomes the most
 * sensitive table in the database, which is the opposite of what it is for.
 *
 * <p><strong>A user action names its user.</strong> {@code actorId} is nullable only because a
 * SYSTEM action has no user behind it; the database rejects a USER row without one, because an
 * unattributable audit entry is not an audit entry.
 *
 * <p>There are no setters, by construction.
 */
@Entity
@Table(name = "audit_log")
public class AuditLogEntry extends AbstractEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 16, updatable = false)
    private ActorType actorType;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "action", nullable = false, length = 64, updatable = false)
    private String action;

    @Column(name = "resource_type", nullable = false, length = 32, updatable = false)
    private String resourceType;

    @Column(name = "resource_id", updatable = false)
    private UUID resourceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_state", updatable = false)
    private String beforeState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_state", updatable = false)
    private String afterState;

    @Column(name = "correlation_id", nullable = false, updatable = false)
    private UUID correlationId;

    @Column(name = "trace_id", length = 32, updatable = false)
    private String traceId;

    @CreationTimestamp
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected AuditLogEntry() {}

    @SuppressWarnings("checkstyle:ParameterNumber")
    private AuditLogEntry(
            ActorType actorType,
            UUID actorId,
            String action,
            String resourceType,
            UUID resourceId,
            String beforeState,
            String afterState,
            UUID correlationId,
            String traceId) {
        this.actorType = actorType;
        this.actorId = actorId;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.beforeState = beforeState;
        this.afterState = afterState;
        this.correlationId = correlationId;
        this.traceId = traceId;
    }

    /** Something a person did. The user is required. */
    public static AuditLogEntry byUser(
            UUID actorId,
            String action,
            String resourceType,
            UUID resourceId,
            String beforeState,
            String afterState,
            UUID correlationId,
            String traceId) {
        return new AuditLogEntry(
                ActorType.USER,
                actorId,
                action,
                resourceType,
                resourceId,
                beforeState,
                afterState,
                correlationId,
                traceId);
    }

    /** Something SentinelFlow did on its own: a relay publication, a sweep, an automatic closure. */
    public static AuditLogEntry bySystem(
            String action,
            String resourceType,
            UUID resourceId,
            String beforeState,
            String afterState,
            UUID correlationId,
            String traceId) {
        return new AuditLogEntry(
                ActorType.SYSTEM,
                null,
                action,
                resourceType,
                resourceId,
                beforeState,
                afterState,
                correlationId,
                traceId);
    }

    public ActorType getActorType() {
        return actorType;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getAction() {
        return action;
    }

    public String getResourceType() {
        return resourceType;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public String getBeforeState() {
        return beforeState;
    }

    public String getAfterState() {
        return afterState;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public String getTraceId() {
        return traceId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
