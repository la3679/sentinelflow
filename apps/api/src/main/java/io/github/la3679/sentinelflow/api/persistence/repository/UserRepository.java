/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.github.la3679.sentinelflow.api.domain.RoleCode;
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

    /**
     * One operator by the name they type in, for the login path.
     *
     * <p>The username is unique and lower-case by constraint (V1), so this cannot match two rows and
     * cannot be defeated by case. Whether the account is usable is decided by the caller, not by the
     * query: a disabled user is found and then refused, which keeps "no such user" and "that user
     * cannot log in" from being the same absence.
     */
    Optional<User> findByUsername(String username);

    /**
     * The roles a user holds, as the codes the token will carry.
     *
     * <p>A join through {@code user_roles} rather than a mapped collection on {@link User}. The
     * association is not needed anywhere else, and adding one would put a lazy collection on an
     * entity that is otherwise loaded for its identifier alone.
     */
    @Query("""
            SELECT r.code FROM Role r, UserRole ur
             WHERE ur.id.roleId = r.id
               AND ur.id.userId = :userId
             ORDER BY r.code
            """)
    List<RoleCode> findRoleCodes(@Param("userId") UUID userId);
}
