/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

import org.hibernate.proxy.HibernateProxy;

import io.github.la3679.sentinelflow.api.domain.UuidV7;

/**
 * The identifier every SentinelFlow entity carries, and the equality that follows from it.
 *
 * <p><strong>Identifiers are assigned in the constructor, not by the database.</strong> A UUIDv7
 * generated in the application is available before the row is written, which means an entity is
 * fully identified the moment it exists - so it can be put in a collection, referenced by another
 * entity being built in the same unit of work, and logged with the identifier the row will
 * actually have. A database-generated key forces a flush to learn its own identity. See ADR-0007.
 *
 * <p><strong>Equality is by identifier, carefully.</strong> Two rules make it correct under
 * Hibernate. First, {@code getClass()} comparison is wrong for a lazy proxy, whose class is a
 * generated subclass; the proxy's real type has to be unwrapped. Second, an entity that has not
 * been assigned an identifier would make every unsaved instance equal to every other - which
 * cannot happen here, because the constructor always assigns one.
 *
 * <p><strong>hashCode is constant across the entity's life.</strong> It comes from the class, not
 * the identifier, so an entity added to a {@code HashSet} before a flush is still findable
 * afterwards. That is the standard Hibernate compromise: a slightly weaker hash in exchange for
 * one that never changes underneath a collection.
 */
@MappedSuperclass
public abstract class AbstractEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UuidV7.randomUuid();

    public UUID getId() {
        return id;
    }

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null) {
            return false;
        }
        Class<?> thisType = effectiveClass(this);
        Class<?> otherType = effectiveClass(other);
        if (thisType != otherType) {
            return false;
        }
        return id.equals(((AbstractEntity) other).id);
    }

    @Override
    public final int hashCode() {
        return effectiveClass(this).hashCode();
    }

    @Override
    public String toString() {
        return effectiveClass(this).getSimpleName() + "[" + id + "]";
    }

    private static Class<?> effectiveClass(Object candidate) {
        return candidate instanceof HibernateProxy proxy
                ? proxy.getHibernateLazyInitializer().getPersistentClass()
                : candidate.getClass();
    }
}
