/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web.limit;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What this API will accept from one caller: how big a request may be, and how many of them (ADR-0017
 * §2, §3).
 *
 * <p><strong>Every number here defaults</strong>, unlike the JWT secret and the ingestion key, which
 * do not. A default limit grants nothing to anybody; a default secret is a secret everybody has. The
 * failure modes are opposite and so are the rules.
 *
 * <p>The three rate categories exist because the right limit for a login attempt and the right limit
 * for a transaction feed differ by two orders of magnitude, and one number that suited both would
 * suit neither.
 *
 * @param maxRequestBytes the largest body accepted under {@code /api/v1/}. The largest legitimate one
 *     is a transaction of a few hundred bytes; the largest a person writes is a 2,000-character note.
 * @param login the bucket for {@code POST /api/v1/auth/login} — the endpoint that runs BCrypt against
 *     a supplied password, and the one an unlimited caller would use to try a million of them
 * @param ingest the bucket for {@code POST /api/v1/transactions}, sized so a replay burst is not
 *     shaped by it
 * @param standard the bucket for everything else under {@code /api/v1/}
 */
@ConfigurationProperties("sentinelflow.limits")
public record RequestLimitProperties(long maxRequestBytes, Bucket login, Bucket ingest, Bucket standard) {

    /** Below this a legitimate transaction would not fit, so a smaller cap is a misconfiguration. */
    static final long MINIMUM_REQUEST_BYTES = 1024;

    public RequestLimitProperties {
        if (maxRequestBytes < MINIMUM_REQUEST_BYTES) {
            throw new IllegalArgumentException("sentinelflow.limits.max-request-bytes is " + maxRequestBytes
                    + ", below the " + MINIMUM_REQUEST_BYTES + " a legitimate transaction needs. A cap that refuses "
                    + "every real request is an outage rather than a limit.");
        }
        require(login, "login");
        require(ingest, "ingest");
        require(standard, "standard");
    }

    private static void require(Bucket bucket, String name) {
        if (bucket == null) {
            throw new IllegalArgumentException("sentinelflow.limits." + name + " is required. Every request under "
                    + "/api/v1/ falls into exactly one category, so a missing one would leave requests uncounted.");
        }
    }

    /**
     * One token bucket's configuration: a sustained rate, and how much of it may be spent at once.
     *
     * @param permits tokens added per {@code per}. The sustained rate.
     * @param per the window those permits are added over
     * @param burst the bucket's capacity, and therefore the most a caller may spend in an instant. At
     *     least {@code permits} would make burst and rate the same thing; allowing it to be smaller is
     *     deliberate, because a login limit wants no burst at all beyond its own allowance.
     */
    public record Bucket(long permits, Duration per, long burst) {

        public Bucket {
            if (permits <= 0) {
                throw new IllegalArgumentException(
                        "A rate limit bucket needs a positive permit count. Zero would refuse every "
                                + "request, which is a way of turning the API off rather than of limiting it.");
            }
            if (per == null || per.isZero() || per.isNegative()) {
                throw new IllegalArgumentException("A rate limit bucket needs a positive window. A window of zero "
                        + "makes the refill rate infinite, which is the same as having no limit.");
            }
            if (burst <= 0) {
                throw new IllegalArgumentException("A rate limit bucket needs a positive burst. A capacity of zero "
                        + "leaves no token for the first request, so nothing would ever be served.");
            }
        }

        /** Nanoseconds one token takes to refill. Computed once, because the filter is on every request. */
        long nanosPerPermit() {
            return per.toNanos() / permits;
        }
    }
}
