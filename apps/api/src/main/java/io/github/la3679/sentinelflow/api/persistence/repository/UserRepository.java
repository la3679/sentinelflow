/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /**
     * One page of the operators an alert may actually be given to.
     *
     * <p><strong>Two conditions, and each excludes somebody for a different reason.</strong> The
     * status filter leaves out a disabled account, because giving work to somebody who cannot sign
     * in is a queue entry nobody will ever clear. The role filter leaves out an
     * {@code AUDITOR} - read-only by ADR-0012 §4 - and the {@code system} principal, which has no
     * credential and raises alerts rather than working them.
     *
     * <p>This is the same rule the assignment path enforces, and that is the point: a picker that
     * offered somebody the server would refuse would be drawing a control that does not work, which
     * {@code docs/development/ENGINEERING_STANDARDS.md} forbids. <strong>It is an affordance and not the
     * authorization</strong> - the server still checks on the way in, and
     * {@code AlertOperationsIT} proves it by assigning to an auditor and getting a refusal.
     *
     * <p>Ordered by display name so a picker reads alphabetically, with the identifier breaking ties
     * so two operators sharing a name cannot make paging unstable.
     */
    @Query(value = """
            SELECT u FROM User u
             WHERE u.status = io.github.la3679.sentinelflow.api.domain.UserStatus.ACTIVE
               AND EXISTS (
                   SELECT 1 FROM UserRole ur, Role r
                    WHERE ur.id.userId = u.id
                      AND ur.id.roleId = r.id
                      AND r.code IN (
                          io.github.la3679.sentinelflow.api.domain.RoleCode.ANALYST,
                          io.github.la3679.sentinelflow.api.domain.RoleCode.ADMINISTRATOR))
             ORDER BY u.displayName, u.id
            """, countQuery = """
            SELECT count(u) FROM User u
             WHERE u.status = io.github.la3679.sentinelflow.api.domain.UserStatus.ACTIVE
               AND EXISTS (
                   SELECT 1 FROM UserRole ur, Role r
                    WHERE ur.id.userId = u.id
                      AND ur.id.roleId = r.id
                      AND r.code IN (
                          io.github.la3679.sentinelflow.api.domain.RoleCode.ANALYST,
                          io.github.la3679.sentinelflow.api.domain.RoleCode.ADMINISTRATOR))
            """)
    Page<User> findAssignableOperators(Pageable pageable);

    /**
     * Every role held by any of these operators, in one query.
     *
     * <p>Answers the roles for a whole page at once. The alternative is
     * {@link #findRoleCodes(UUID)} per row, which is the N+1 a twenty-name picker would pay twenty
     * times.
     *
     * <p>An empty collection is the caller's to avoid: {@code IN ()} is not valid SQL and Hibernate
     * renders it as a predicate that is never true, which happens to be the right answer here but
     * only by accident. {@code OperatorDirectory} returns early instead.
     */
    @Query("""
            SELECT new io.github.la3679.sentinelflow.api.persistence.repository.OperatorRoleRow(
                       ur.id.userId, r.code)
              FROM Role r, UserRole ur
             WHERE ur.id.roleId = r.id
               AND ur.id.userId IN :operatorIds
             ORDER BY r.code
            """)
    List<OperatorRoleRow> findRoleCodesFor(@Param("operatorIds") Collection<UUID> operatorIds);
}
