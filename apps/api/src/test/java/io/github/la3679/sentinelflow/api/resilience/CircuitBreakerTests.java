/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The breaker, driven by a clock a test controls rather than by sleeping.
 *
 * <p>A test that waits thirty seconds to assert a timer is a test nobody runs, and a suite nobody
 * runs is a suite that stops being true. The clock is a constructor argument for exactly this.
 */
class CircuitBreakerTests {

    private static final Duration OPEN_FOR = Duration.ofSeconds(30);
    private static final int THRESHOLD = 5;

    /** A clock a test moves by hand. Not {@code Clock.offset}, which needs a new instance each step. */
    private static final class MovableClock extends Clock {

        private Instant now = Instant.parse("2026-08-27T12:00:00Z");

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }
    }

    private final MovableClock clock = new MovableClock();
    private final CircuitBreaker breaker = new CircuitBreaker("test", THRESHOLD, OPEN_FOR, clock);

    @Test
    @DisplayName("a new breaker is closed and allows everything")
    void startsClosed() {
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.allowsRequest()).isTrue();
    }

    @Test
    @DisplayName("it opens on the threshold, not before it")
    void opensOnTheThreshold() {
        for (int failure = 1; failure < THRESHOLD; failure++) {
            breaker.recordFailure();
            assertThat(breaker.state())
                    .as("%d consecutive failures is below the threshold of %d", failure, THRESHOLD)
                    .isEqualTo(CircuitBreaker.State.CLOSED);
        }

        breaker.recordFailure();

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.allowsRequest()).isFalse();
    }

    @Test
    @DisplayName("one success resets the run, because the failures have to be consecutive")
    void successResetsTheRun() {
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordSuccess();

        // Four more would open a breaker that had counted the earlier four.
        for (int failure = 0; failure < THRESHOLD - 1; failure++) {
            breaker.recordFailure();
        }

        assertThat(breaker.state())
                .as("a rate would have opened here; consecutive is the whole point")
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("it stays open for the whole window and then lets exactly one probe through")
    void oneProbeAfterTheWindow() {
        open();

        clock.advance(OPEN_FOR.minusSeconds(1));
        assertThat(breaker.allowsRequest()).as("one second short of the window").isFalse();

        clock.advance(Duration.ofSeconds(1));
        assertThat(breaker.allowsRequest()).as("the probe").isTrue();
        assertThat(breaker.allowsRequest())
                .as("a second caller at the same instant would be a second probe, and an outage that "
                        + "has just ended should not be met with the whole backlog")
                .isFalse();
    }

    @Test
    @DisplayName("a probe that succeeds closes the breaker")
    void aSuccessfulProbeCloses() {
        open();
        clock.advance(OPEN_FOR);
        assertThat(breaker.allowsRequest()).isTrue();

        breaker.recordSuccess();

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.allowsRequest()).isTrue();
    }

    @Test
    @DisplayName("a probe that fails re-opens for a full window rather than resuming the count")
    void aFailedProbeReopens() {
        open();
        clock.advance(OPEN_FOR);
        assertThat(breaker.allowsRequest()).isTrue();

        breaker.recordFailure();

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        clock.advance(OPEN_FOR.minusSeconds(1));
        assertThat(breaker.allowsRequest())
                .as("the probe is the evidence; one failing says as much as five ordinary calls failing")
                .isFalse();
    }

    @Test
    @DisplayName("a probe whose caller never reports does not shut the breaker forever")
    void anAbandonedProbeDoesNotLockOut() {
        open();
        clock.advance(OPEN_FOR);
        assertThat(breaker.allowsRequest()).isTrue();

        // No recordSuccess, no recordFailure. That is a caller bug, and it must
        // cost a window rather than every future request.
        clock.advance(OPEN_FOR);

        assertThat(breaker.allowsRequest())
                .as("a permanent lockout is too expensive to leave to a caller remembering its finally")
                .isTrue();
    }

    @Test
    @DisplayName("exactly one of many concurrent callers becomes the probe")
    void onlyOneProbeUnderConcurrency() throws Exception {
        open();
        clock.advance(OPEN_FOR);

        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int index = 0; index < threads; index++) {
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (breaker.allowsRequest()) {
                        allowed.incrementAndGet();
                    }
                }));
            }

            start.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        }

        assertThat(allowed.get())
                .as("the breaker is shared by every thread that scores, so the race is real rather "
                        + "than theoretical")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("nonsensical settings are rejected at construction")
    void rejectsNonsense() {
        assertThatThrownBy(() -> new CircuitBreaker("test", 0, OPEN_FOR, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before anything failed");

        assertThatThrownBy(() -> new CircuitBreaker("test", THRESHOLD, Duration.ZERO, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never protects anything");
    }

    private void open() {
        for (int failure = 0; failure < THRESHOLD; failure++) {
            breaker.recordFailure();
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}
