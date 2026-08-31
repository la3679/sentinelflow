/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web.limit;

/**
 * Whether a rate-limited request may proceed, and if not, how long until it could.
 *
 * <p>A record rather than a boolean, because the refusal carries the one thing a caller needs and a
 * boolean would make the filter compute separately — and computing it twice is how the header and
 * the decision end up disagreeing.
 *
 * @param allowed whether a token was spent
 * @param retryAfterNanos how long until one would be available. Meaningless when allowed.
 */
public record Decision(boolean allowed, long retryAfterNanos) {

    private static final Decision GRANTED = new Decision(true, 0);

    static Decision granted() {
        return GRANTED;
    }

    static Decision refused(long retryAfterNanos) {
        return new Decision(false, retryAfterNanos);
    }

    /**
     * The {@code Retry-After} value, in whole seconds, never below one.
     *
     * <p>RFC 9110 says the header is seconds, and a rounded-down zero would tell a caller to retry
     * immediately — which is exactly what got them refused.
     */
    public long retryAfterSeconds() {
        return Math.max(1, (retryAfterNanos + 999_999_999L) / 1_000_000_000L);
    }
}
