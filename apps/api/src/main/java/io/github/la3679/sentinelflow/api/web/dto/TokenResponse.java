/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web.dto;

import java.time.Instant;

/**
 * What a successful login returns.
 *
 * <p>{@code tokenType} is {@code Bearer} and is sent rather than assumed, because it is what the
 * client has to put in front of the value in the {@code Authorization} header, and a client that
 * hardcoded it would be guessing correctly.
 *
 * <p>{@code expiresAt} travels beside the token so a client knows when to log in again without
 * decoding it. A client that parsed the token for the claim would be reading a structure this
 * service is free to change.
 *
 * <p><strong>No refresh token.</strong> There is none to send: ADR-0012 §3 takes a short expiry
 * instead of a refresh flow, and a field that was always null would suggest one exists.
 */
public record TokenResponse(String token, String tokenType, Instant expiresAt) {

    private static final String BEARER = "Bearer";

    public static TokenResponse bearer(String token, Instant expiresAt) {
        return new TokenResponse(token, BEARER, expiresAt);
    }

    @Override
    public String toString() {
        // The token is a credential for as long as it is valid. A log line
        // holding one is a log line somebody can authenticate with.
        return "TokenResponse[tokenType=" + tokenType + ", expiresAt=" + expiresAt + ", token=***]";
    }
}
