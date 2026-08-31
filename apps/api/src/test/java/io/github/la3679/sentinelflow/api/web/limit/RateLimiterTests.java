/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.la3679.sentinelflow.api.web.limit.RateLimiter.Category;
import io.github.la3679.sentinelflow.api.web.limit.RequestLimitProperties.Bucket;

/**
 * The bucket registry: who is counted against whom, and what stops the map growing without bound.
 *
 * <p>{@link TokenBucketTests} covers the arithmetic. What is asserted here is the bookkeeping around
 * it, which is where a limiter's real defects live — two callers sharing a bucket, two categories
 * sharing one, or a map a caller can grow by inventing keys.
 */
class RateLimiterTests {

    private static final Bucket THREE_PER_MINUTE = new Bucket(3, Duration.ofMinutes(1), 3);
    private static final Bucket ONE_PER_MINUTE = new Bucket(1, Duration.ofMinutes(1), 1);

    private final AtomicLong clock = new AtomicLong();

    private RateLimiter limiter(Bucket login, Bucket ingest, Bucket standard) {
        return new RateLimiter(new RequestLimitProperties(65_536, login, ingest, standard), clock::get);
    }

    @Test
    @DisplayName("two callers have two allowances, so one of them cannot spend the other's")
    void countsCallersSeparately() {
        RateLimiter limiter = limiter(THREE_PER_MINUTE, THREE_PER_MINUTE, THREE_PER_MINUTE);

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(limiter.tryAcquire(Category.STANDARD, "addr:10.0.0.1").allowed())
                    .isTrue();
        }
        assertThat(limiter.tryAcquire(Category.STANDARD, "addr:10.0.0.1").allowed())
                .as("the first caller is out")
                .isFalse();
        assertThat(limiter.tryAcquire(Category.STANDARD, "addr:10.0.0.2").allowed())
                .as("and the second one is not")
                .isTrue();
    }

    @Test
    @DisplayName("one caller's categories are separate allowances, so a spent login does not close the API")
    void countsCategoriesSeparately() {
        RateLimiter limiter = limiter(ONE_PER_MINUTE, THREE_PER_MINUTE, THREE_PER_MINUTE);

        assertThat(limiter.tryAcquire(Category.LOGIN, "addr:10.0.0.1").allowed())
                .isTrue();
        assertThat(limiter.tryAcquire(Category.LOGIN, "addr:10.0.0.1").allowed())
                .as("the login allowance is one, and it is gone")
                .isFalse();
        assertThat(limiter.tryAcquire(Category.STANDARD, "addr:10.0.0.1").allowed())
                .as("the same caller can still read: failing to sign in is not being locked out of the API")
                .isTrue();
    }

    @Test
    @DisplayName("the login category takes its own configuration, not another category's")
    void usesTheConfigurationForItsCategory() {
        RateLimiter limiter = limiter(ONE_PER_MINUTE, THREE_PER_MINUTE, THREE_PER_MINUTE);

        assertThat(limiter.tryAcquire(Category.LOGIN, "caller").allowed()).isTrue();
        assertThat(limiter.tryAcquire(Category.LOGIN, "caller").allowed()).isFalse();

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(limiter.tryAcquire(Category.INGEST, "caller").allowed())
                    .as("ingest attempt %d", attempt + 1)
                    .isTrue();
        }
        assertThat(limiter.tryAcquire(Category.INGEST, "caller").allowed()).isFalse();
    }

    @Test
    @DisplayName("an allowance comes back as time passes, rather than being spent for good")
    void refillsWithTheInjectedClock() {
        RateLimiter limiter = limiter(ONE_PER_MINUTE, ONE_PER_MINUTE, ONE_PER_MINUTE);

        assertThat(limiter.tryAcquire(Category.STANDARD, "caller").allowed()).isTrue();
        assertThat(limiter.tryAcquire(Category.STANDARD, "caller").allowed()).isFalse();

        clock.set(Duration.ofMinutes(1).toNanos());

        assertThat(limiter.tryAcquire(Category.STANDARD, "caller").allowed()).isTrue();
    }

    @Test
    @DisplayName("the map has a ceiling, so a caller inventing keys degrades the limit rather than the heap")
    void boundsTheNumberOfBucketsHeld() {
        RateLimiter limiter = limiter(THREE_PER_MINUTE, THREE_PER_MINUTE, THREE_PER_MINUTE);

        // Twice the ceiling, every one a distinct key, which is what a flood of
        // forged X-API-Key headers looks like from in here.
        for (int caller = 0; caller < RateLimiter.MAX_TRACKED * 2; caller++) {
            clock.addAndGet(1);
            limiter.tryAcquire(Category.STANDARD, "addr:invented-" + caller);
        }

        assertThat(limiter.tracked())
                .as("the map is bounded rather than as large as the attacker chose")
                .isLessThanOrEqualTo(RateLimiter.MAX_TRACKED);
    }

    @Test
    @DisplayName("a caller still being seen survives the eviction that drops the idle ones")
    void keepsTheActiveCallerWhenItEvicts() {
        RateLimiter limiter = limiter(THREE_PER_MINUTE, THREE_PER_MINUTE, THREE_PER_MINUTE);

        limiter.tryAcquire(Category.STANDARD, "addr:the-real-caller");
        limiter.tryAcquire(Category.STANDARD, "addr:the-real-caller");

        for (int caller = 0; caller < RateLimiter.MAX_TRACKED + 100; caller++) {
            clock.addAndGet(1);
            limiter.tryAcquire(Category.STANDARD, "addr:invented-" + caller);
            // Touched throughout, so it is among the most recently seen when the
            // eviction runs. An eviction that dropped it would hand a caller
            // being limited a fresh allowance by flooding the map, which is the
            // bound working as a bypass.
            limiter.tryAcquire(Category.STANDARD, "addr:the-real-caller");
        }

        assertThat(limiter.tryAcquire(Category.STANDARD, "addr:the-real-caller").allowed())
                .as("its three are long spent and the clock has barely moved")
                .isFalse();
    }

    @Test
    @DisplayName("a bucket with no permits is refused at startup rather than refusing every request")
    void refusesAMeaninglessConfiguration() {
        assertThatThrownBy(() -> new Bucket(0, Duration.ofMinutes(1), 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive permit count");

        assertThatThrownBy(() -> new Bucket(10, Duration.ZERO, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive window");

        assertThatThrownBy(() -> new Bucket(10, Duration.ofMinutes(1), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive burst");
    }

    @Test
    @DisplayName("a request cap below a real transaction is refused at startup, because it is an outage")
    void refusesAnUnusableRequestCap() {
        assertThatThrownBy(() -> new RequestLimitProperties(16, THREE_PER_MINUTE, THREE_PER_MINUTE, THREE_PER_MINUTE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("below the");
    }

    @Test
    @DisplayName("a missing category is refused at startup, because it would leave requests uncounted")
    void refusesAMissingCategory() {
        assertThatThrownBy(() -> new RequestLimitProperties(65_536, null, THREE_PER_MINUTE, THREE_PER_MINUTE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("login");
    }
}
