/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web.limit;

import java.util.concurrent.atomic.AtomicReference;

import io.github.la3679.sentinelflow.api.web.limit.RequestLimitProperties.Bucket;

/**
 * One caller's allowance in one category: a token bucket, refilled continuously (ADR-0017 §2).
 *
 * <h2>Why a token bucket rather than a fixed window</h2>
 *
 * A fixed window resets on a clock boundary, so a caller limited to 10 a minute can send 20 in two
 * seconds by straddling one — half at 11:59:59 and half at 12:00:00. That is not an edge case, it is
 * the first thing anybody trying finds. A token bucket has no boundary to straddle: tokens accrue at
 * a fixed rate and the capacity is the whole of what may be spent at once.
 *
 * <h2>Why the state is one immutable record behind a compare-and-set</h2>
 *
 * Two fields have to move together — the token count and the instant they were last computed — and a
 * pair of separate atomics can be read between one another's writes, which is how a limiter ends up
 * granting tokens twice. Holding both in one record makes the update a single successful
 * {@code compareAndSet} or a retry, and there is no lock for a request thread to wait on.
 *
 * <h2>Nanoseconds, from a monotonic clock</h2>
 *
 * {@link System#nanoTime()} rather than the wall clock, because the wall clock steps: an NTP
 * correction backwards would make the elapsed time negative and refill the bucket by a negative
 * amount, and one forwards would hand a caller their whole allowance. A limiter must not be
 * adjustable by the system clock.
 */
final class TokenBucket {

    /** Tokens are held scaled by this, so a partial refill is not lost to integer division. */
    private static final long SCALE = 1_000_000L;

    private final long capacityScaled;
    private final long nanosPerPermit;
    private final AtomicReference<State> state;

    TokenBucket(Bucket configuration, long nowNanos) {
        this.capacityScaled = configuration.burst() * SCALE;
        this.nanosPerPermit = configuration.nanosPerPermit();
        // Starts full. A caller's first request should not be refused because
        // the service has only just started.
        this.state = new AtomicReference<>(new State(capacityScaled, nowNanos));
    }

    /**
     * Spends one token if there is one.
     *
     * @return a granted decision, or a refusal carrying how long until a token would be available
     */
    Decision tryAcquire(long nowNanos) {
        while (true) {
            State current = state.get();
            long refilled = refill(current, nowNanos);

            if (refilled < SCALE) {
                // Not enough for one token. Report how long until there is,
                // rounded up: telling a caller to come back a nanosecond early
                // is telling them to be refused twice.
                long shortfall = SCALE - refilled;
                long waitNanos = (shortfall * nanosPerPermit + SCALE - 1) / SCALE;
                return Decision.refused(waitNanos);
            }

            State next = new State(refilled - SCALE, nowNanos);
            if (state.compareAndSet(current, next)) {
                return Decision.granted();
            }
            // Lost the race. Re-read and recompute rather than assume: another
            // thread has spent a token, and this one's arithmetic is now stale.
        }
    }

    /** The scaled token count as of {@code nowNanos}, capped at the bucket's capacity. */
    private long refill(State current, long nowNanos) {
        long elapsed = nowNanos - current.updatedNanos();
        if (elapsed <= 0) {
            // A monotonic clock does not go backwards, but two threads can read
            // it in either order. Treating that as no elapsed time is correct
            // and is not the same as trusting the value.
            return current.tokensScaled();
        }
        long added = elapsed / nanosPerPermit * SCALE + elapsed % nanosPerPermit * SCALE / nanosPerPermit;
        return Math.min(capacityScaled, current.tokensScaled() + added);
    }

    /** Token count and the instant it was computed at, moved together or not at all. */
    private record State(long tokensScaled, long updatedNanos) {}
}
