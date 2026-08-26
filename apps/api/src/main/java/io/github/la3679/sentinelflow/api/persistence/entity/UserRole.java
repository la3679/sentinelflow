/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;

/**
 * A role held by a user.
 *
 * <p>Modelled as an entity rather than as a {@code @ManyToMany} collection on {@link User}. A
 * collection would load every grant whenever a user is touched and would give the join table
 * nowhere to put {@code granted_at}; an explicit entity keeps both the timing and the loading under
 * control.
 *
 * <p>The only place in this schema whose foreign key cascades on delete: a grant says something
 * about a user and means nothing without one. Everything else points at history and restricts.
 */
@Entity
@Table(name = "user_roles")
public class UserRole {

    @EmbeddedId
    private UserRoleId id;

    @CreationTimestamp
    @Column(name = "granted_at", nullable = false, updatable = false)
    private Instant grantedAt;

    protected UserRole() {}

    public UserRole(UUID userId, UUID roleId) {
        this.id = new UserRoleId(userId, roleId);
    }

    public UserRoleId getId() {
        return id;
    }

    public Instant getGrantedAt() {
        return grantedAt;
    }
}
