/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web.limit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.github.la3679.sentinelflow.api.web.limit.RequestLimitProperties.Bucket;

/**
 * Every caller's buckets, in memory, bounded (ADR-0017 §2).
 *
 * <h2>The map is the part that needs care, not the arithmetic</h2>
 *
 * A rate limiter keyed on something a caller controls is a memory leak with a key: a million distinct
 * addresses is a million entries, and nothing removes them. Two things bound it. Entries are evicted
 * once a bucket has been full and untouched for longer than its own window — a full bucket carries no
 * information, so forgetting it changes no decision. And the map has a hard ceiling: at
 * {@link #MAX_TRACKED} the oldest half is dropped rather than the map being allowed to grow, which
 * degrades the limit under a key-flooding attack instead of turning the attack into an
 * {@code OutOfMemoryError}.
 *
 * <p>The ceiling is deliberately the same shape as {@code RetryStateTracker}'s, and for the same
 * reason: a tracking structure whose size is chosen by a caller is not a tracking structure.
 *
 * <h2>The clock is injected</h2>
 *
 * Not for elegance — a rate limiter cannot be tested against real time without the test taking real
 * seconds, and a test that sleeps is a test that is flaky on a loaded machine. The supplier is
 * {@link System#nanoTime} in production and a controllable value under test, which is what lets the
 * refill arithmetic be asserted at all.
 */
@Component
public class RateLimiter {

    /**
     * The most buckets held at once, across every category.
     *
     * <p>Roughly a megabyte of entries. Large enough that no legitimate demo approaches it, small
     * enough that a flood of forged keys cannot exhaust the heap.
     */
    static final int MAX_TRACKED = 20_000;

    private final RequestLimitProperties limits;
    private final LongSupplier clock;
    private final Map<Key, Entry> buckets = new ConcurrentHashMap<>();

    // Two constructors, so the one the container uses has to say so: without
    // the annotation Spring finds two candidates and refuses to guess, which it
    // reports as "no default constructor found" - a message that names the one
    // constructor that does not exist rather than the two that do.
    @Autowired
    public RateLimiter(RequestLimitProperties limits) {
        this(limits, System::nanoTime);
    }

    /** The clock is a parameter so a test can move time without sleeping. */
    RateLimiter(RequestLimitProperties limits, LongSupplier clock) {
        this.limits = limits;
        this.clock = clock;
    }

    /**
     * Spends one token from {@code caller}'s bucket in {@code category}.
     *
     * @return the decision, carrying the retry delay when it is a refusal
     */
    public Decision tryAcquire(Category category, String caller) {
        long now = clock.getAsLong();
        evictIfCrowded(now);

        Entry entry = buckets.computeIfAbsent(
                new Key(category, caller), key -> new Entry(new TokenBucket(configurationFor(category), now)));
        entry.touchedNanos = now;
        return entry.bucket.tryAcquire(now);
    }

    /** How many buckets are currently held. For the test that proves the ceiling is real. */
    int tracked() {
        return buckets.size();
    }

    /**
     * Forgets every allowance.
     *
     * <p>Package-private and reachable from nothing that serves a request — there is no endpoint, no
     * actuator operation and no configuration that calls it. It exists because the limiter is a
     * singleton and its state is the point: without it, one test method's spent bucket is the next
     * one's starting position, and the suite's results depend on the order JUnit happened to pick.
     */
    void clear() {
        buckets.clear();
    }

    private Bucket configurationFor(Category category) {
        return switch (category) {
            case LOGIN -> limits.login();
            case INGEST -> limits.ingest();
            case STANDARD -> limits.standard();
        };
    }

    /**
     * Drops the least recently used half when the map reaches its ceiling.
     *
     * <p>Half rather than one entry, so the scan is amortised over the next ten thousand requests
     * instead of running on every one of them once the ceiling is reached.
     */
    private void evictIfCrowded(long now) {
        if (buckets.size() < MAX_TRACKED) {
            return;
        }
        long[] touched = buckets.values().stream()
                .mapToLong(entry -> entry.touchedNanos)
                .sorted()
                .toArray();
        long cutoff = touched[touched.length / 2];
        buckets.values().removeIf(entry -> entry.touchedNanos <= cutoff);
    }

    /** Which allowance a request is counted against. */
    public enum Category {
        /** {@code POST /api/v1/auth/login}: the endpoint that checks a password. */
        LOGIN,
        /** {@code POST /api/v1/transactions}: the ingestion feed. */
        INGEST,
        /** Everything else under {@code /api/v1/}. */
        STANDARD
    }

    /** A caller in a category. Two categories are two independent allowances for the same caller. */
    private record Key(Category category, String caller) {}

    /** A bucket and when it was last reached for, which is what makes eviction possible. */
    private static final class Entry {
        private final TokenBucket bucket;
        private volatile long touchedNanos;

        private Entry(TokenBucket bucket) {
            this.bucket = bucket;
        }
    }
}
