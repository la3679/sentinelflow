/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * What one user logs in with (ADR-0012 §2).
 *
 * <p><strong>The user's own identifier is the primary key.</strong> One credential per user, made
 * so by the schema rather than by a rule the application applies: a second row for the same user
 * would be a second password, and "which one is current" is a question nobody should be able to ask.
 *
 * <p><strong>A user without a row here cannot log in, and the system principal is one.</strong> That
 * is the whole reason this is a table rather than a nullable column on {@code users} — the login
 * path cannot find what does not exist, so authenticating as the principal that attributes automated
 * actions is impossible rather than merely forbidden.
 *
 * <p>{@code AbstractEntity} is deliberately not extended: it assigns a UUIDv7 identifier of its own
 * (ADR-0007), and this row's identity is the user's.
 */
@Entity
@Table(name = "user_credentials")
public class UserCredential {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "password_hash", nullable = false, length = 120)
    private String passwordHash;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserCredential() {}

    /**
     * @param passwordHash an already-encoded hash. Never a plaintext password: this class does no
     *     encoding, so a caller that passed one would store it, and the column's CHECK is what
     *     refuses it.
     */
    public UserCredential(UUID userId, String passwordHash) {
        this.userId = userId;
        this.passwordHash = passwordHash;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    /** Rotation. The same rule applies: the argument is an encoded hash, never a password. */
    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
