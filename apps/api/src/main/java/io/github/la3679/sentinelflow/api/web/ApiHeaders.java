/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web;

/**
 * Request and response header names this API defines for itself.
 *
 * <p>Here rather than on whichever class happened to need one first, because two of them are read by
 * more than one filter: the rate limiter keys a caller on the ingestion key without knowing anything
 * else about it, and the ingestion filter compares that same key against the configured one. Two
 * spellings of one header is a bug that presents as an intermittently unauthenticated caller, and
 * nothing would name it.
 *
 * <p>{@code X-Correlation-Id} is deliberately not here: it lives on {@link CorrelationIdFilter} with
 * the attribute and MDC keys it belongs beside.
 */
public final class ApiHeaders {

    /** The ingestion credential (ADR-0017 §1). Read by the rate limiter, checked by the key filter. */
    public static final String API_KEY = "X-API-Key";

    private ApiHeaders() {}
}
