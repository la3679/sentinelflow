/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.domain;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/**
 * Generates RFC 9562 version 7 UUIDs: time-ordered, so inserts append to the primary-key index
 * instead of scattering across it.
 *
 * <p>ADR-0007 requires UUIDv7 for every identifier in this system. The JDK offers {@code
 * UUID.randomUUID()} (version 4) and nothing else, and PostgreSQL 18's {@code uuidv7()} only helps
 * rows the database itself inserts. Application-assigned identifiers therefore need this.
 *
 * <p>Layout, most significant bit first:
 *
 * <pre>
 *   48 bits  unix_ts_ms      milliseconds since the epoch, big-endian
 *    4 bits  version         0b0111
 *   12 bits  counter         monotonic within a millisecond (see below)
 *    2 bits  variant         0b10
 *   62 bits  random          from a CSPRNG
 * </pre>
 *
 * <p><strong>Monotonicity.</strong> A bare timestamp plus randomness is only ordered across
 * milliseconds; within one millisecond two identifiers sort arbitrarily. This implementation
 * spends the 12 bits of {@code rand_a} on a counter that increments for every identifier issued in
 * the same millisecond, which is the "replace leftmost random bits with a counter" method RFC 9562
 * section 6.2 describes. The result is strictly increasing for a single generator, which is what
 * makes index locality hold under a burst rather than only under a trickle.
 *
 * <p>4096 identifiers in one millisecond exhausts the counter. Rather than wrap - which would emit
 * a smaller identifier than one already issued, silently breaking the ordering guarantee this class
 * exists to provide - generation waits for the clock to advance. That ceiling is four million per
 * second, far above anything this project will produce.
 *
 * <p><strong>Thread safety.</strong> Instances are safe for concurrent use; {@link #generate()} is
 * synchronised on the generator, which is uncontended relative to the database write that follows
 * every identifier this issues.
 *
 * <p>Identifiers are not secrets. The randomness here is for uniqueness and unguessability, not for
 * authorization: a UUID that is hard to guess is still not a capability, and every endpoint checks
 * access on its own.
 */
public final class UuidV7 {

    private static final long VERSION_7 = 0x7000L;
    private static final long VARIANT_RFC_9562 = 0x8000_0000_0000_0000L;
    private static final int COUNTER_BITS = 12;
    private static final int COUNTER_MAX = (1 << COUNTER_BITS) - 1;
    private static final long STALLED_CLOCK_TIMEOUT_NANOS = 100_000_000L;

    private static final UuidV7 SHARED = new UuidV7(Clock.systemUTC(), new SecureRandom());

    private final Clock clock;
    private final SecureRandom random;

    private long lastTimestampMillis = -1L;
    private int counter;

    UuidV7(Clock clock, SecureRandom random) {
        this.clock = clock;
        this.random = random;
    }

    /**
     * Returns the shared generator.
     *
     * <p>One generator per process rather than one per call site: the monotonicity guarantee is a
     * property of a generator's own sequence, and two generators sharing a millisecond can each
     * issue an ordered sequence while producing an unordered union.
     */
    public static UuidV7 shared() {
        return SHARED;
    }

    /** Convenience for the common case. Equivalent to {@code shared().generate()}. */
    public static UUID randomUuid() {
        return SHARED.generate();
    }

    /** Returns the next identifier, strictly greater than every identifier this generator issued. */
    public synchronized UUID generate() {
        long now = clock.millis();

        if (now > lastTimestampMillis) {
            lastTimestampMillis = now;
            counter = 0;
        } else {
            // now <= last: either the same millisecond, or the clock went
            // backwards (NTP correction, a suspended laptop resuming). Both are
            // handled the same way - keep issuing from the last timestamp we
            // used, so the sequence never goes back on itself.
            if (counter >= COUNTER_MAX) {
                lastTimestampMillis = awaitNextMillis(lastTimestampMillis);
                counter = 0;
            } else {
                counter++;
            }
        }

        long high = (lastTimestampMillis << 16) | VERSION_7 | counter;
        long low = (random.nextLong() >>> 2) | VARIANT_RFC_9562;

        return new UUID(high, low);
    }

    /**
     * Reads the embedded timestamp.
     *
     * <p>Useful in diagnostics and in tests. It is not a substitute for a {@code created_at}
     * column: the identifier records when the identifier was minted, which is not necessarily when
     * the row was committed, and nothing should join or filter on it.
     *
     * @throws IllegalArgumentException if the argument is not a version 7 UUID
     */
    public static long timestampMillis(UUID uuid) {
        if (uuid.version() != 7) {
            throw new IllegalArgumentException("Not a version 7 UUID: " + uuid);
        }
        return uuid.getMostSignificantBits() >>> 16;
    }

    private long awaitNextMillis(long previous) {
        // Bounded. A real clock advances within a millisecond, so this returns
        // almost immediately; a clock that does not advance at all is a broken
        // environment, and failing loudly beats spinning forever inside a
        // synchronized block that every other writer is queued behind.
        long deadline = System.nanoTime() + STALLED_CLOCK_TIMEOUT_NANOS;
        long now = clock.millis();
        while (now <= previous) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException(
                        "Clock did not advance past " + previous + "ms; cannot issue a monotonic UUIDv7");
            }
            Thread.onSpinWait();
            now = clock.millis();
        }
        return now;
    }
}
