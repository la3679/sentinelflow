/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.backoff.BackOffExecution;

/**
 * The retry schedule, which is a decision in ADR-0006 §4 rather than a tuning knob.
 *
 * <p>Two properties matter and neither is visible by reading the class: that the budget is genuinely
 * bounded, and that the delay is drawn across the <em>whole</em> window rather than clustered at its
 * end. The second is what stops every record that failed during an outage retrying in lockstep the
 * instant it recovers.
 */
class FullJitterBackOffTests {

    private static final Duration BASE = Duration.ofMillis(100);
    private static final Duration CEILING = Duration.ofSeconds(10);

    private static List<Long> drain(FullJitterBackOff backOff) {
        List<Long> delays = new ArrayList<>();
        BackOffExecution execution = backOff.start();
        for (long next = execution.nextBackOff(); next != BackOffExecution.STOP; next = execution.nextBackOff()) {
            delays.add(next);
            // A guard rather than a bound: an unbounded schedule would hang this
            // test instead of failing it.
            if (delays.size() > 100) {
                break;
            }
        }
        return delays;
    }

    @Test
    @DisplayName("the budget is bounded: five attempts means four waits, then stop")
    void stopsAfterTheAttemptBudget() {
        // maxAttempts counts deliveries. The first is not a retry, so a budget
        // of five permits four waits before the record is recovered.
        assertThat(drain(new FullJitterBackOff(BASE, CEILING, 5))).hasSize(4);
    }

    @Test
    @DisplayName("a budget of one means no retry at all")
    void oneAttemptNeverWaits() {
        assertThat(drain(new FullJitterBackOff(BASE, CEILING, 1))).isEmpty();
    }

    @Test
    @DisplayName("every delay is inside its own exponential window, and never past the ceiling")
    void staysInsideTheWindow() {
        for (int run = 0; run < 200; run++) {
            List<Long> delays = drain(new FullJitterBackOff(BASE, CEILING, 8));

            for (int attempt = 0; attempt < delays.size(); attempt++) {
                long window = Math.min(BASE.toMillis() << (attempt + 1), CEILING.toMillis());
                assertThat(delays.get(attempt))
                        .as("attempt %d of run %d is inside [0, %d)", attempt, run, window)
                        .isGreaterThanOrEqualTo(0L)
                        .isLessThan(window);
            }
        }
    }

    @Test
    @DisplayName("the delay is spread across the window, not clustered at its end")
    void drawsAcrossTheWholeWindow() {
        // The property that distinguishes full jitter from a fixed delay with a
        // wobble. Sampling the first wait 400 times, whose window is 200ms:
        // a fixed schedule would put every sample in one place, and Spring's own
        // +/- jitter would put them all near the top.
        long lowest = Long.MAX_VALUE;
        long highest = Long.MIN_VALUE;
        for (int run = 0; run < 400; run++) {
            long first = new FullJitterBackOff(BASE, CEILING, 5).start().nextBackOff();
            lowest = Math.min(lowest, first);
            highest = Math.max(highest, first);
        }

        long window = BASE.toMillis() << 1;
        // Generous bounds: this asserts the shape of the distribution, not a
        // particular draw, so it must not fail one run in a thousand on CI.
        assertThat(lowest).isLessThan(window / 4);
        assertThat(highest).isGreaterThan(window * 3 / 4);
    }

    @Test
    @DisplayName("a long budget cannot overflow the window into a negative delay")
    void doesNotOverflow() {
        // The shift is what makes this worth asserting: base << 40 is negative,
        // and a negative delay is a retry that never waits.
        assertThat(drain(new FullJitterBackOff(BASE, CEILING, 50))).hasSize(49).allSatisfy(delay -> assertThat(delay)
                .isBetween(0L, CEILING.toMillis()));
    }
}
