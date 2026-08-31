/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web.limit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.la3679.sentinelflow.api.web.limit.RequestLimitProperties.Bucket;

/**
 * The refill arithmetic, driven against a clock this test controls.
 *
 * <p>Nothing here sleeps. A rate limiter tested against real time takes real seconds and is flaky on
 * a loaded machine, which is the reason the bucket takes the instant as a parameter rather than
 * reading it — the testability is the design, not a concession to it.
 */
class TokenBucketTests {

    /** Ten a minute: one token every six seconds, and a burst of ten. */
    private static final Bucket TEN_PER_MINUTE = new Bucket(10, Duration.ofMinutes(1), 10);

    private static final long SECOND = Duration.ofSeconds(1).toNanos();

    @Test
    @DisplayName("a new bucket starts full, so a first request is never refused by a cold start")
    void startsFull() {
        TokenBucket bucket = new TokenBucket(TEN_PER_MINUTE, 0);

        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(bucket.tryAcquire(0).allowed())
                    .as("attempt %d of the burst", attempt + 1)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("the eleventh request in an instant is refused, because the burst is ten")
    void refusesBeyondTheBurst() {
        TokenBucket bucket = new TokenBucket(TEN_PER_MINUTE, 0);
        for (int attempt = 0; attempt < 10; attempt++) {
            bucket.tryAcquire(0);
        }

        assertThat(bucket.tryAcquire(0).allowed()).isFalse();
    }

    @Test
    @DisplayName("a refusal says how long until a token exists, rounded up rather than down")
    void reportsTheWait() {
        TokenBucket bucket = new TokenBucket(TEN_PER_MINUTE, 0);
        for (int attempt = 0; attempt < 10; attempt++) {
            bucket.tryAcquire(0);
        }

        Decision refused = bucket.tryAcquire(0);

        // One token every six seconds, and the bucket is empty.
        assertThat(refused.retryAfterNanos()).isEqualTo(6 * SECOND);
        assertThat(refused.retryAfterSeconds()).isEqualTo(6);
    }

    @Test
    @DisplayName("a refusal never reports zero seconds, which would mean retry immediately")
    void neverReportsAnImmediateRetry() {
        TokenBucket bucket = new TokenBucket(TEN_PER_MINUTE, 0);
        for (int attempt = 0; attempt < 10; attempt++) {
            bucket.tryAcquire(0);
        }

        // Five and a bit seconds in: a fraction of a token short, which rounds
        // to zero whole seconds and must not be reported as one.
        Decision refused = bucket.tryAcquire(5 * SECOND + SECOND / 2);

        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterSeconds()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("tokens accrue with elapsed time, one every six seconds at ten a minute")
    void refillsOverTime() {
        TokenBucket bucket = new TokenBucket(TEN_PER_MINUTE, 0);
        for (int attempt = 0; attempt < 10; attempt++) {
            bucket.tryAcquire(0);
        }

        assertThat(bucket.tryAcquire(5 * SECOND).allowed())
                .as("five seconds is not yet a token")
                .isFalse();
        assertThat(bucket.tryAcquire(6 * SECOND).allowed())
                .as("six seconds is one token")
                .isTrue();
        assertThat(bucket.tryAcquire(6 * SECOND).allowed())
                .as("and it was spent")
                .isFalse();
    }

    @Test
    @DisplayName("a partial refill is carried rather than discarded")
    void doesNotLosePartialRefills() {
        TokenBucket bucket = new TokenBucket(TEN_PER_MINUTE, 0);
        for (int attempt = 0; attempt < 10; attempt++) {
            bucket.tryAcquire(0);
        }

        // Three seconds twice is six seconds, and must produce a token. Integer
        // division on each call separately would round both to nothing and
        // starve a caller who polls.
        assertThat(bucket.tryAcquire(3 * SECOND).allowed()).isFalse();
        assertThat(bucket.tryAcquire(6 * SECOND).allowed()).isTrue();
    }

    @Test
    @DisplayName("an idle bucket refills to its burst and no further")
    void doesNotAccrueBeyondTheBurst() {
        TokenBucket bucket = new TokenBucket(TEN_PER_MINUTE, 0);
        for (int attempt = 0; attempt < 10; attempt++) {
            bucket.tryAcquire(0);
        }

        // An hour idle. Sixty tokens' worth of time, ten tokens' worth of
        // capacity - otherwise a caller who waits banks an unbounded allowance,
        // which is the failure mode a leaky-bucket-shaped bug produces.
        long anHour = Duration.ofHours(1).toNanos();
        int granted = 0;
        for (int attempt = 0; attempt < 50; attempt++) {
            if (bucket.tryAcquire(anHour).allowed()) {
                granted++;
            }
        }

        assertThat(granted).isEqualTo(10);
    }

    @Test
    @DisplayName("a clock that appears to go backwards neither grants nor removes a token")
    void toleratesAnApparentlyBackwardClock() {
        TokenBucket bucket = new TokenBucket(TEN_PER_MINUTE, 100 * SECOND);
        for (int attempt = 0; attempt < 10; attempt++) {
            bucket.tryAcquire(100 * SECOND);
        }

        // Two threads can read a monotonic clock in either order. The earlier
        // value must be treated as no elapsed time, not as a negative refill.
        assertThat(bucket.tryAcquire(90 * SECOND).allowed()).isFalse();
        assertThat(bucket.tryAcquire(106 * SECOND).allowed())
                .as("and the bucket has not been corrupted by it")
                .isTrue();
    }

    @Test
    @DisplayName("concurrent callers are granted exactly the burst between them, never more")
    void grantsExactlyTheBurstUnderContention() throws Exception {
        int threads = 16;
        int attemptsEach = 40;
        TokenBucket bucket = new TokenBucket(new Bucket(100, Duration.ofMinutes(1), 100), 0);
        AtomicInteger granted = new AtomicInteger();
        AtomicLong frozenClock = new AtomicLong(0);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int thread = 0; thread < threads; thread++) {
                pool.execute(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int attempt = 0; attempt < attemptsEach; attempt++) {
                        if (bucket.tryAcquire(frozenClock.get()).allowed()) {
                            granted.incrementAndGet();
                        }
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        // The clock never moves, so no token refills: 640 attempts against a
        // capacity of 100 must grant exactly 100. A compare-and-set that lost a
        // race would grant more, which is the whole reason the state is one
        // record rather than two atomics.
        assertThat(granted.get()).isEqualTo(100);
    }
}
