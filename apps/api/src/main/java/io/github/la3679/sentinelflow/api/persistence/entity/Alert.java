/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import io.github.la3679.sentinelflow.api.domain.AlertPriority;
import io.github.la3679.sentinelflow.api.domain.AlertStatus;
import io.github.la3679.sentinelflow.api.domain.RiskBand;

/**
 * A piece of work on an analyst's queue.
 *
 * <p><strong>The optimistic lock is not optional here.</strong> Two analysts opening the same alert
 * and both acting is the normal case in a shared queue, not an edge case. The loser of that race
 * has to be told, because the alternative is that one analyst's disposition silently replaces
 * another's and neither of them knows.
 *
 * <p><strong>{@code version} starts at 0, and the contract now says so.</strong> Hibernate seeds a
 * new {@code @Version} at 0; the OpenAPI schema required {@code minimum: 1} and was amended to
 * {@code minimum: 0} rather than the mapping being bent to match it. The version is an opaque
 * concurrency token - a client echoes it back as {@code expectedVersion} and must never read
 * meaning into its magnitude - so the honest fix was the one that did not add a translation layer
 * whose only job is to hide the ORM's counter from a client that cannot interpret it anyway.
 *
 * <p><strong>{@code closedAt} is set by the transition, not by the caller.</strong> A terminal
 * alert has a close time and a live one does not; the database enforces that, and
 * {@link #transitionTo} is what keeps application code on the right side of it.
 */
@Entity
@Table(name = "alerts")
public class Alert extends AbstractEntity {

    /** Statuses that mean the investigation is over. Mirrors {@code alerts_closed_at_consistent}. */
    private static final Set<AlertStatus> TERMINAL =
            EnumSet.of(AlertStatus.CONFIRMED_SUSPICIOUS, AlertStatus.DISMISSED_FALSE_POSITIVE, AlertStatus.CLOSED);

    @Column(name = "alert_reference", nullable = false, length = 16, updatable = false)
    private String alertReference;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "assessment_id", nullable = false, updatable = false)
    private UUID assessmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private AlertStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 16)
    private AlertPriority priority;

    @Column(name = "assignee_id")
    private UUID assigneeId;

    @Column(name = "summary", nullable = false, length = 500)
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_band", nullable = false, length = 16, updatable = false)
    private RiskBand riskBand;

    @Column(name = "final_score", nullable = false, precision = 5, scale = 2, updatable = false)
    private BigDecimal finalScore;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected Alert() {}

    public Alert(
            String alertReference,
            UUID transactionId,
            UUID assessmentId,
            AlertPriority priority,
            String summary,
            RiskBand riskBand,
            BigDecimal finalScore) {
        this.alertReference = alertReference;
        this.transactionId = transactionId;
        this.assessmentId = assessmentId;
        this.priority = priority;
        this.summary = summary;
        this.riskBand = riskBand;
        this.finalScore = finalScore;
        this.status = AlertStatus.NEW;
    }

    /**
     * Moves the alert and keeps {@code closedAt} consistent with where it moved to.
     *
     * @param target the status to move to
     * @param at the moment the transition happened, used as the close time when the target is
     *     terminal
     */
    public void transitionTo(AlertStatus target, Instant at) {
        this.status = target;
        this.closedAt = TERMINAL.contains(target) ? at : null;
    }

    public boolean isTerminal() {
        return TERMINAL.contains(status);
    }

    public String getAlertReference() {
        return alertReference;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public UUID getAssessmentId() {
        return assessmentId;
    }

    public AlertStatus getStatus() {
        return status;
    }

    public AlertPriority getPriority() {
        return priority;
    }

    public void setPriority(AlertPriority priority) {
        this.priority = priority;
    }

    public UUID getAssigneeId() {
        return assigneeId;
    }

    public void setAssigneeId(UUID assigneeId) {
        this.assigneeId = assigneeId;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public RiskBand getRiskBand() {
        return riskBand;
    }

    public BigDecimal getFinalScore() {
        return finalScore;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }
}
