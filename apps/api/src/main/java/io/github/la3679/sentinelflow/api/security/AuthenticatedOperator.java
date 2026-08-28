/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.security;

import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;

import io.github.la3679.sentinelflow.api.domain.Actor;
import io.github.la3679.sentinelflow.api.domain.ActorRole;

/**
 * Turns the verified token on a request into the {@link Actor} an audit row is written with.
 *
 * <h2>Why a single role has to be chosen, and how</h2>
 *
 * A user may hold several roles; {@code alert_actions.actor_role} records exactly one, because an
 * audit trail answers "in what capacity" and a list is not an answer. The role recorded is the
 * <strong>most privileged one the user held at login</strong>: an administrator who is also an
 * analyst acted as an administrator, since that is the authority the action rested on.
 *
 * <p>The ordering is explicit rather than the enum's declaration order. {@link ActorRole} is a
 * domain enum whose members could be reordered for readability by somebody with no idea that an
 * audit trail depended on it.
 *
 * <h2>SYSTEM is not reachable from here</h2>
 *
 * A token cannot carry it: the system principal has no credential row, and the login path refuses
 * the role explicitly besides. Automated actions build their actor from
 * {@link Actor#system(UUID)} instead, so the two paths cannot be confused for one another in the
 * history.
 */
public final class AuthenticatedOperator {

    /** Most privileged first. The first match is the capacity the action is recorded under. */
    private static final List<ActorRole> BY_AUTHORITY =
            List.of(ActorRole.ADMINISTRATOR, ActorRole.ANALYST, ActorRole.AUDITOR);

    private AuthenticatedOperator() {}

    /**
     * The actor a mutation should be attributed to.
     *
     * @param token the verified token, which the resource server has already checked the signature
     *     and expiry of. Nothing here re-validates it; a token that reached this method is one the
     *     filter chain accepted.
     * @throws IllegalStateException if the token carries no recognised role. Not a 403: a token
     *     this service signed and cannot interpret is a defect in the issuer rather than a caller
     *     doing something they are not allowed to.
     */
    public static Actor from(Jwt token) {
        UUID userId = UUID.fromString(token.getSubject());
        List<String> roles = token.getClaimAsStringList(SecurityConfiguration.ROLES_CLAIM);

        for (ActorRole candidate : BY_AUTHORITY) {
            if (roles != null && roles.contains(candidate.name())) {
                return new Actor(userId, candidate);
            }
        }
        throw new IllegalStateException("The token for " + userId + " carries no role this service recognises. "
                + "It was signed here, so this is an issuing defect rather than a caller's mistake.");
    }
}
