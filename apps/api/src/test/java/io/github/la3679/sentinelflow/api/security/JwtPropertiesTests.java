/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The refusals that happen at startup rather than at the first login.
 *
 * <p>Every case here is a misconfiguration that would otherwise produce a service which starts,
 * reports healthy, and cannot authenticate anybody — or worse, one that authenticates everybody
 * against a key short enough to be weaker than the algorithm it names. A configuration error that
 * surfaces on the first request is one that surfaces in production.
 */
class JwtPropertiesTests {

    private static final String VALID_SECRET = "a-signing-key-that-is-long-enough-for-hs256";
    private static final Duration VALID_EXPIRY = Duration.ofMinutes(30);
    private static final String VALID_ISSUER = "sentinelflow-api";

    @Test
    @DisplayName("a complete configuration is accepted")
    void acceptsAValidConfiguration() {
        assertThatCode(() -> new JwtProperties(VALID_SECRET, VALID_EXPIRY, VALID_ISSUER))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a missing secret is refused, and the message says what to set")
    void refusesAMissingSecret() {
        // The empty placeholder in application.yaml resolves to this, which is
        // deliberate: it fails as loudly as an unresolved placeholder would and
        // says what to set rather than which property could not be bound.
        assertThatThrownBy(() -> new JwtProperties("", VALID_EXPIRY, VALID_ISSUER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SENTINELFLOW_JWT_SECRET");
    }

    @Test
    @DisplayName("a secret too short for the algorithm is refused, and never echoed")
    void refusesAShortSecret() {
        String tooShort = "x".repeat(JwtProperties.MINIMUM_SECRET_BYTES - 1);

        assertThatThrownBy(() -> new JwtProperties(tooShort, VALID_EXPIRY, VALID_ISSUER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("31 bytes")
                // The length and not the value. A message that echoed the
                // secret would put it in a log on the one path guaranteed to be
                // read.
                .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain(tooShort));
    }

    @Test
    @DisplayName("exactly the minimum length is accepted, because it is the minimum and not the floor above it")
    void acceptsTheMinimumLength() {
        assertThatCode(() ->
                        new JwtProperties("x".repeat(JwtProperties.MINIMUM_SECRET_BYTES), VALID_EXPIRY, VALID_ISSUER))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an expiry that is zero, negative or absent is refused")
    void refusesANonPositiveExpiry() {
        assertThatThrownBy(() -> new JwtProperties(VALID_SECRET, Duration.ZERO, VALID_ISSUER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive duration");
        assertThatThrownBy(() -> new JwtProperties(VALID_SECRET, Duration.ofMinutes(-1), VALID_ISSUER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JwtProperties(VALID_SECRET, null, VALID_ISSUER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an expiry longer than the project allows is refused")
    void refusesAnExcessiveExpiry() {
        // A stateless token cannot be revoked, so its lifetime is the whole of
        // how long a withdrawn role keeps working. A day-long token would make
        // "we removed their access" untrue for a day.
        assertThatThrownBy(() ->
                        new JwtProperties(VALID_SECRET, JwtProperties.MAXIMUM_EXPIRY.plusMinutes(1), VALID_ISSUER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be revoked");
    }

    @Test
    @DisplayName("a missing issuer is refused")
    void refusesAMissingIssuer() {
        // Without it, a token signed for another environment with the same
        // secret verifies here - which is exactly what a shared secret between
        // a staging stack and a demo would produce.
        assertThatThrownBy(() -> new JwtProperties(VALID_SECRET, VALID_EXPIRY, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("issuer is required");
    }
}
