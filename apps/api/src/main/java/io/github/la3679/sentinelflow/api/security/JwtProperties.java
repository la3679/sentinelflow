/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The token this service issues and verifies (ADR-0012 §1, §6).
 *
 * <p><strong>The secret has no default, and startup fails without one.</strong> The same rule
 * {@code POSTGRES_PASSWORD} already follows, for the same reason: a default secret is a secret
 * everybody has, and one that shipped in this file would sign valid operator tokens on every
 * deployment that forgot to override it. {@code make bootstrap} generates one into the git-ignored
 * {@code .env}.
 *
 * <p><strong>The length is checked here rather than trusted.</strong> HMAC-SHA256 keys shorter than
 * the hash they feed are weaker than the algorithm they claim, and nothing downstream reports it —
 * Nimbus refuses a key under 256 bits, but it refuses at the first login rather than at startup, and
 * a service that starts and cannot authenticate anybody is a worse failure than one that does not
 * start.
 *
 * @param secret the HMAC key, at least 32 characters. Never logged, never returned, never defaulted.
 * @param expiry how long an issued token is valid. Short, because a stateless token cannot be
 *     revoked and the expiry is the only thing bounding a role change (ADR-0012 §3).
 * @param issuer the {@code iss} claim, so a token from another service or another environment does
 *     not verify here merely because it was signed with a shared secret.
 */
@ConfigurationProperties("sentinelflow.security.jwt")
public record JwtProperties(String secret, Duration expiry, String issuer) {

    /** 256 bits, which is what HMAC-SHA256 needs and what Nimbus refuses to go below. */
    static final int MINIMUM_SECRET_BYTES = 32;

    /** Long enough to be useful and short enough that a revoked role stops mattering the same day. */
    static final Duration MAXIMUM_EXPIRY = Duration.ofHours(12);

    public JwtProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("sentinelflow.security.jwt.secret is required and has no default. "
                    + "Set SENTINELFLOW_JWT_SECRET; `make bootstrap` generates one into .env.");
        }
        int bytes = secret.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < MINIMUM_SECRET_BYTES) {
            // The length and not the value. A message that echoed the secret
            // would put it in a log on the one path guaranteed to be read.
            throw new IllegalArgumentException("sentinelflow.security.jwt.secret is " + bytes
                    + " bytes and HMAC-SHA256 "
                    + "needs at least " + MINIMUM_SECRET_BYTES + ". A shorter key is weaker than the algorithm it "
                    + "claims, and nothing downstream would say so.");
        }
        if (expiry == null || expiry.isZero() || expiry.isNegative()) {
            throw new IllegalArgumentException(
                    "sentinelflow.security.jwt.expiry must be a positive duration. A token that expires "
                            + "on issue is not a token, and one that never expires cannot be revoked at all.");
        }
        if (expiry.compareTo(MAXIMUM_EXPIRY) > 0) {
            throw new IllegalArgumentException("sentinelflow.security.jwt.expiry is " + expiry + ", above the "
                    + MAXIMUM_EXPIRY + " this project allows. A stateless token cannot be revoked, so its lifetime "
                    + "is the whole of how long a withdrawn role keeps working.");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("sentinelflow.security.jwt.issuer is required. Without it a token "
                    + "signed for another environment with the same secret verifies here.");
        }
    }
}
