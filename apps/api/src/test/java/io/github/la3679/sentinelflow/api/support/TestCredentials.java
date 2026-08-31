/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.support;

/**
 * The throwaway credentials every test context is started with.
 *
 * <p>Constants rather than literals scattered across the suites, so a test that has to present one
 * cannot drift from the context that accepts it — a mismatch there produces a 401 in a test about
 * something else entirely, which is among the least informative failures available.
 *
 * <p><strong>None of these is a secret in any sense that matters.</strong> They exist because
 * {@code application.yaml} deliberately gives the real ones no usable default (ADR-0012 §6,
 * ADR-0017 §1), so a context cannot start without something in the slot. They authenticate against a
 * container that is destroyed when the fork ends.
 */
public final class TestCredentials {

    /**
     * The JWT signing key. Long enough for HS256, which {@code JwtProperties} enforces.
     */
    public static final String JWT_SECRET = "a-test-signing-key-of-sufficient-length-for-hs256";

    /**
     * The ingestion key, presented as {@code X-API-Key} by anything posting a transaction. At least
     * 32 characters, which {@code IngestionProperties} enforces.
     */
    public static final String INGEST_API_KEY = "a-test-ingestion-key-of-sufficient-length";

    private TestCredentials() {}
}
