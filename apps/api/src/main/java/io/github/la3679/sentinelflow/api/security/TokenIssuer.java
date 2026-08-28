/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.security;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import io.github.la3679.sentinelflow.api.domain.RoleCode;

/**
 * Turns an authenticated operator into the token they will present.
 *
 * <h2>What the token says, and what it deliberately does not</h2>
 *
 * <p>{@code sub} is the user's <strong>identifier</strong>, not their username. Every audit row this
 * token leads to writes {@code actor_id}, which is a foreign key into {@code users}; carrying the
 * username instead would mean a lookup on every mutation to turn a name back into the key it came
 * from, and the token would stop being self-contained for no gain.
 *
 * <p>{@code roles} carries the codes the user held <em>at login</em>, which is what makes the
 * audit trail honest: {@code alert_actions.actor_role} records the role an actor was exercising, and
 * a role read fresh from the database at mutation time would record what they hold now instead.
 *
 * <p>Nothing else. No display name, no email, no permissions expanded from roles. A claim is a copy
 * of something, and every copy is a thing that can be stale — a token is not a place to cache a
 * profile.
 *
 * <h2>The expiry is the only revocation there is</h2>
 *
 * A stateless token cannot be withdrawn before it expires (ADR-0012 §3). {@code exp} is therefore
 * short, and {@code iat} is stamped so a consumer can tell a fresh token from a re-presented old one
 * without asking this service anything.
 */
@Component
public class TokenIssuer {

    private final JwtEncoder encoder;
    private final JwtProperties properties;

    public TokenIssuer(JwtEncoder encoder, JwtProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    /**
     * A signed token for one operator.
     *
     * @param userId the {@code users} row this token acts as
     * @param roles the codes held at this moment. An operator with none gets a token that
     *     authenticates and authorizes nothing, which is the honest representation of an account
     *     whose roles have all been withdrawn.
     * @param issuedAt taken from the caller so the token, the response and any log line agree
     */
    public IssuedToken issue(UUID userId, List<RoleCode> roles, Instant issuedAt) {
        Instant expiresAt = issuedAt.plus(properties.expiry());
        List<RoleCode> held = List.copyOf(roles);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(userId.toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim(
                        SecurityConfiguration.ROLES_CLAIM,
                        held.stream().map(RoleCode::name).toList())
                .build();

        // The algorithm is stated rather than left to the encoder's default.
        // A header this service did not choose is a header nobody reviewed.
        String value = encoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();

        return new IssuedToken(value, expiresAt, held);
    }

    /**
     * A token, when it stops working, and what it authorizes.
     *
     * <p>The expiry and the roles travel beside the token rather than only inside it, so a client
     * need decode nothing to know when to log in again or which controls to offer. A client that
     * parsed the token for either would be reading a structure this service is free to change.
     *
     * <p>The roles are the same list that went into the claim, taken from the same call rather than
     * read again — two reads could disagree, and the one the audit trail would record is the one
     * inside the token.
     */
    public record IssuedToken(String value, Instant expiresAt, List<RoleCode> roles) {
        public IssuedToken {
            roles = List.copyOf(roles);
        }
    }
}
