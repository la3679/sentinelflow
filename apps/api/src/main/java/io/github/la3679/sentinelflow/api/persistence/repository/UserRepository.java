/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import io.github.la3679.sentinelflow.api.persistence.entity.User;

/** Users. */
public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * The system principal's identifier.
     *
     * <p>V1 inserts it as reference data rather than demo data, and its comment says why: an
     * automated alert transition cannot be recorded without an actor, {@code alert_actions.actor_id}
     * is {@code NOT NULL}, and an unattributable change to a reviewed decision must not be
     * representable.
     *
     * <p>The identifier rather than the entity, because nothing here needs the row. Read once per
     * assessment that raises an alert, on the unique index behind {@code users_username_unique}.
     */
    @Query("SELECT u.id FROM User u WHERE u.username = 'system'")
    Optional<UUID> findSystemPrincipalId();
}
