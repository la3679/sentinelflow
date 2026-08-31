/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.security;

import java.nio.charset.StandardCharsets;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The credential the ingestion endpoint requires (ADR-0017 §1).
 *
 * <p><strong>No default, and startup fails without one</strong>, the same rule
 * {@link JwtProperties#secret()} and {@code POSTGRES_PASSWORD} follow. A default here would be worse
 * than either: it is a single shared secret that grants the right to write to the database and the
 * outbox, and one that shipped in this file would grant it on every deployment that forgot to
 * override it. {@code make bootstrap} generates one into the git-ignored {@code .env}.
 *
 * <p><strong>The length is checked rather than trusted.</strong> Nothing downstream would report a
 * four-character key — it would authenticate perfectly and be guessable in an afternoon. A minimum is
 * the only place that fact can be caught, and startup is the only time anybody is looking.
 *
 * @param apiKey the shared secret a caller presents in {@code X-API-Key}. Never logged, never
 *     returned, never defaulted, and compared in constant time.
 */
@ConfigurationProperties("sentinelflow.security.ingestion")
public record IngestionProperties(String apiKey) {

    /**
     * 32 characters.
     *
     * <p>The same floor as the JWT secret, chosen for the same reason rather than by analogy: a
     * shared secret guessable by anybody who can reach the endpoint is not a credential, and the
     * endpoint it protects is the one that writes.
     */
    static final int MINIMUM_KEY_LENGTH = 32;

    public IngestionProperties {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("sentinelflow.security.ingestion.api-key is required and has no "
                    + "default. Set SENTINELFLOW_INGEST_API_KEY; `make bootstrap` generates one into .env. "
                    + "Ingestion writes to the database and the outbox, so an absent credential is an open "
                    + "write endpoint rather than a missing convenience.");
        }
        int length = apiKey.length();
        if (length < MINIMUM_KEY_LENGTH) {
            // The length and not the value, for the reason JwtProperties gives:
            // a message that echoed the secret would put it in a log on the one
            // path guaranteed to be read.
            throw new IllegalArgumentException("sentinelflow.security.ingestion.api-key is " + length
                    + " characters and needs at least " + MINIMUM_KEY_LENGTH
                    + ". A short shared secret authenticates perfectly and is guessable, which nothing "
                    + "downstream would report.");
        }
    }

    /** The key as bytes, for a constant-time comparison. */
    byte[] apiKeyBytes() {
        return apiKey.getBytes(StandardCharsets.UTF_8);
    }
}
