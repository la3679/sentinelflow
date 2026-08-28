/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.domain;

import java.util.UUID;

/**
 * Who is doing something, and in what capacity.
 *
 * <p>Both halves travel together because both are written to every audit row. The identifier
 * answers "who", and {@code alert_actions.actor_id} is {@code NOT NULL} so that an unattributable
 * change to a reviewed decision is not representable. The role answers "as what", and it is
 * recorded rather than looked up later because a user's roles change and an audit trail has to say
 * what was true at the time.
 *
 * <p>A parameter rather than something a service reads from a security context. The service that
 * transitions an alert is the same code whether the actor is a person holding a token or the
 * pipeline raising an alert, and a hidden read of the current authentication would make the second
 * case impossible to write and the first impossible to test without one.
 *
 * @param userId a row in {@code users}. The foreign key is what makes this checkable.
 * @param role the role the actor held for this action
 */
public record Actor(UUID userId, ActorRole role) {

    public Actor {
        if (userId == null) {
            throw new IllegalArgumentException("An action with no actor is not attributable, and the schema refuses "
                    + "to store one. Pass the system principal for an automated action.");
        }
        if (role == null) {
            throw new IllegalArgumentException("An actor with no role cannot be audited: alert_actions.actor_role is "
                    + "NOT NULL, and 'unknown' is not one of its four values.");
        }
    }

    /** The pipeline acting on its own, attributed to the principal V1 inserts as reference data. */
    public static Actor system(UUID systemPrincipalId) {
        return new Actor(systemPrincipalId, ActorRole.SYSTEM);
    }
}
