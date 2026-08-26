/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;

import io.github.la3679.sentinelflow.api.domain.RoleCode;

/**
 * One of the four principal roles.
 *
 * <p>Reference data, inserted by {@code V1__identity_and_reference_data.sql}. Nothing in the
 * application creates a role: a fifth role is a migration and a change to {@link RoleCode}, not a
 * row someone inserts at runtime.
 */
@Entity
@Table(name = "roles")
public class Role extends AbstractEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "code", nullable = false, length = 32, updatable = false)
    private RoleCode code;

    @Column(name = "description", nullable = false, length = 200)
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Role() {}

    public RoleCode getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
