/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.resilience;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

/**
 * Exponential backoff with <strong>full jitter</strong>, bounded by an attempt count.
 *
 * <p>ADR-0006 §4 requires full jitter rather than a fixed or purely exponential delay, and the
 * reason is a failure mode rather than a preference: without jitter, everything that failed during a
 * dependency's outage retries in lockstep the instant it recovers and knocks it over again. The
 * outage synchronises the retries and the retries extend the outage.
 *
 * <p>Full jitter is a uniform draw across the <em>whole</em> window — {@code random(0, window)} —
 * not a fixed delay at the window's end and not a small wobble around it. Those two spread a
 * thundering herd across a few percent of the window; this spreads it across all of it.
 *
 * <p>Spring's own {@code ExponentialBackOff} grew a {@code jitter} setting, and it is the wrong
 * shape for this: it perturbs the interval by ±jitter, which is the "small wobble" above. Writing
 * ten lines is cheaper than configuring something into approximately the right behaviour and
 * leaving the next reader to work out which they got.
 *
 * <p><strong>Two callers, deliberately.</strong> The listener container retries a delivery with it,
 * and {@code ScoringClient} retries a single HTTP call with it. Both are a thread sleeping before it
 * tries the same thing again, and both are bounded by an attempt count — so one implementation is
 * the honest arrangement, and it is why this moved out of {@code messaging.consumer} rather than
 * being copied into a second package.
 *
 * <p>The schedule matches {@code OutboxBatchProcessor.backoffFor} deliberately — the same decision
 * appears in ADR-0005 §3 and ADR-0006 §4 — but the two are not shared code. One is a delay written
 * to a database column and read back by a different process; this one is a thread sleeping inside a
 * listener container. A single implementation would have to be parameterised on both, and the
 * coupling would outlive whichever of the two changed first.
 */
public class FullJitterBackOff implements BackOff {

    private final long baseMillis;
    private final long ceilingMillis;
    private final int maxAttempts;

    public FullJitterBackOff(Duration base, Duration ceiling, int maxAttempts) {
        this.baseMillis = base.toMillis();
        this.ceilingMillis = ceiling.toMillis();
        this.maxAttempts = maxAttempts;
    }

    /**
     * The largest total delay this schedule can draw across every retry it permits.
     *
     * <p>Exists so a caller can prove its own budget rather than estimate it, and lives here so the
     * schedule is written once. Computing it from {@code ceiling x retries} instead — the obvious
     * approximation — overstates it badly: the window grows from {@code base} and only reaches the
     * ceiling after several attempts, so the first two retries of the scoring client's schedule can
     * draw at most 200 ms and 400 ms rather than a second each.
     *
     * <p>Each draw is {@code nextLong(0, window)}, exclusive of the bound, so a window contributes at
     * most {@code window - 1}.
     */
    public static Duration worstCaseTotalDelay(Duration base, Duration ceiling, int maxAttempts) {
        long baseMillis = base.toMillis();
        long ceilingMillis = ceiling.toMillis();
        long total = 0;
        for (int failures = 1; failures < maxAttempts; failures++) {
            long window = failures >= 32 ? ceilingMillis : Math.min(baseMillis << failures, ceilingMillis);
            total += Math.max(0, window - 1);
        }
        return Duration.ofMillis(total);
    }

    @Override
    public BackOffExecution start() {
        return new BackOffExecution() {

            private int failures;

            @Override
            public long nextBackOff() {
                failures++;
                // maxAttempts counts deliveries, and this is asked after one has
                // already failed. The (maxAttempts - 1)th question is the last
                // one that may say "wait"; the next says stop, and the record
                // goes to the recoverer.
                if (failures >= maxAttempts) {
                    return BackOffExecution.STOP;
                }

                // Shifted rather than Math.pow, and capped before the shift, so
                // a large attempt count cannot overflow into a negative delay.
                long window = failures >= 32 ? ceilingMillis : Math.min(baseMillis << failures, ceilingMillis);
                // nextLong needs a bound strictly greater than its origin, and a
                // zero-length window would make the jitter a no-op anyway.
                return ThreadLocalRandom.current().nextLong(0, Math.max(1, window));
            }
        };
    }
}
