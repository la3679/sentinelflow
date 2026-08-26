/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import io.github.la3679.sentinelflow.api.domain.UserStatus;

/**
 * A demo operator.
 *
 * <p>Carries no credential of any kind - no password, no hash, no token, no email address. That is
 * not an omission to fill in later: authentication is deferred to its own ADR, and a credential
 * column invented before that decision would become a constraint on it.
 *
 * <p>The row {@code system} inserted by V1 is the principal every automated action is attributed
 * to, which is what lets {@code alert_actions.actor_id} and {@code audit_log.actor_id} be
 * attributable without ever being null for a machine-made change.
 */
@Entity
@Table(name = "users")
public class User extends AbstractEntity {

    @Column(name = "username", nullable = false, length = 64, updatable = false)
    private String username;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private UserStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() {}

    public User(String username, String displayName, UserStatus status) {
        this.username = username;
        this.displayName = displayName;
        this.status = status;
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public UserStatus getStatus() {
        return status;
    }

    public void setStatus(UserStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
