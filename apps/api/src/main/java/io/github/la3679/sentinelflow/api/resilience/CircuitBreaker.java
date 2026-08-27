/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.resilience;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A consecutive-failure circuit breaker.
 *
 * <h2>Why this exists at all</h2>
 *
 * ADR-0008 §3 calls it "the part that matters at scale", and that is a claim about arithmetic rather
 * than about robustness. The scoring call happens inside a Kafka consumer whose retry blocks its
 * partition. Without a breaker, a scoring outage costs every record in the backlog the full HTTP
 * budget before it degrades — so consumer lag grows in proportion to traffic, and a dependency being
 * down becomes a pipeline being down. With one, the first few records pay and the rest degrade
 * immediately.
 *
 * <h2>Why it is written here rather than taken from a library</h2>
 *
 * The behaviour needed is one threshold, one timer and three states. A resilience library brings a
 * dependency, an annotation model, a metrics binder and a configuration surface, for a decision this
 * project has already made in an ADR and would then have to express again in that library's terms.
 * The same reasoning {@link FullJitterBackOff} records: configuring something into approximately the
 * right behaviour leaves the next reader working out which they got.
 *
 * <h2>Consecutive, not a rate</h2>
 *
 * A rolling failure <em>rate</em> is the more common design and is the wrong one here. This breaker
 * guards a single dependency that is either answering or not, on traffic that is bursty by nature —
 * a rate window either opens on a quiet minute with two failures in it, or refuses to open under a
 * flood because the denominator moved. Five in a row is unambiguous.
 *
 * <h2>What counts as a failure is the caller's decision</h2>
 *
 * Nothing here inspects an exception. The caller records a success or a failure, because only the
 * caller knows which outcomes mean "the dependency is sick". For the scoring client a timeout or a
 * 5xx is a failure and a 422 is not: a contract mismatch between two services in one repository is a
 * defect to fix, and opening the breaker on it would convert every request into a degraded
 * assessment and hide the thing that needs fixing.
 *
 * <p>Thread-safe. One instance is shared by every thread that calls the dependency, which is the
 * point — a per-thread breaker would need each thread to learn the outage for itself.
 *
 * <h2>The one obligation on a caller</h2>
 *
 * <strong>A request the breaker allowed must report exactly one outcome.</strong> A caller that
 * returns without recording leaves a half-open probe outstanding and no other call is let through
 * until the window elapses again. {@code ScoringClient} records in a {@code finally}. The stale-probe
 * escape in {@link #allowsRequest()} exists so that a caller which one day does not is a delay rather
 * than a permanently shut breaker.
 */
public class CircuitBreaker {

    /**
     * Where the breaker is.
     *
     * <p><strong>{@code HALF_OPEN} is not a state a request observes.</strong> It is expressed as
     * one probe being let through after the open window elapses; the probe's outcome closes the
     * breaker or opens it again for another window. Modelling it as a durable state would need a
     * count of how many probes are in flight, which is a lock for a case that occurs once per
     * outage.
     */
    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private record Snapshot(State state, int consecutiveFailures, Instant openedAt) {}

    private final String name;
    private final int failureThreshold;
    private final Duration openDuration;
    private final Clock clock;
    private final AtomicReference<Snapshot> snapshot;

    /**
     * @param name what this guards, for logging. Never a URL and never anything from a request.
     * @param failureThreshold consecutive failures before the breaker opens. Must be positive: a
     *     threshold of zero opens before anything has failed.
     * @param openDuration how long it stays open before letting one probe through.
     * @param clock injectable so a test can drive the open window without sleeping. A test that
     *     waits thirty seconds to assert a timer is a test nobody runs.
     */
    public CircuitBreaker(String name, int failureThreshold, Duration openDuration, Clock clock) {
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException(
                    "failureThreshold must be positive; " + failureThreshold + " would open before anything failed");
        }
        if (openDuration == null || openDuration.isNegative() || openDuration.isZero()) {
            throw new IllegalArgumentException("openDuration must be positive; an open window of zero never protects "
                    + "anything, because the next request always probes");
        }
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
        this.clock = clock;
        this.snapshot = new AtomicReference<>(new Snapshot(State.CLOSED, 0, Instant.EPOCH));
    }

    /**
     * Whether a call may proceed.
     *
     * <p>Transitions {@code OPEN} to {@code HALF_OPEN} when the window has elapsed, and returns true
     * for exactly one caller at that moment. Every other caller is turned away until that probe
     * reports back, so an outage that has just ended is not met with the whole backlog at once.
     */
    public boolean allowsRequest() {
        while (true) {
            Snapshot current = snapshot.get();
            Instant now = clock.instant();

            switch (current.state()) {
                case CLOSED:
                    return true;
                case OPEN:
                    if (now.isBefore(current.openedAt().plus(openDuration))) {
                        return false;
                    }
                    break;
                case HALF_OPEN:
                    // A probe is out. Anything else waits for its answer rather
                    // than becoming a second probe — unless the probe is older
                    // than a whole window, in which case its caller never
                    // reported and the breaker would otherwise be shut forever.
                    // That should not happen (see the class comment), and a
                    // permanent lockout is too expensive to leave to should.
                    if (now.isBefore(current.openedAt().plus(openDuration))) {
                        return false;
                    }
                    break;
            }

            // Exactly one caller wins this, and it becomes the probe. Whoever
            // loses goes round again and sees HALF_OPEN.
            Snapshot probing = new Snapshot(State.HALF_OPEN, current.consecutiveFailures(), now);
            if (snapshot.compareAndSet(current, probing)) {
                return true;
            }
        }
    }

    /** The dependency answered. Closes the breaker and forgets the failure run. */
    public void recordSuccess() {
        snapshot.set(new Snapshot(State.CLOSED, 0, Instant.EPOCH));
    }

    /**
     * The dependency did not answer.
     *
     * <p>A failure while half-open re-opens for a full window rather than resuming the count: the
     * probe is the evidence, and one probe failing says as much as five ordinary calls failing.
     */
    public void recordFailure() {
        Instant now = clock.instant();
        snapshot.updateAndGet(current -> {
            if (current.state() == State.HALF_OPEN) {
                return new Snapshot(State.OPEN, current.consecutiveFailures(), now);
            }
            int failures = current.consecutiveFailures() + 1;
            return failures >= failureThreshold
                    ? new Snapshot(State.OPEN, failures, now)
                    : new Snapshot(State.CLOSED, failures, current.openedAt());
        });
    }

    /** The current state, for logging and for tests. Not a decision: use {@link #allowsRequest()}. */
    public State state() {
        return snapshot.get().state();
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        Snapshot current = snapshot.get();
        return "CircuitBreaker[" + name + " " + current.state() + " failures=" + current.consecutiveFailures() + "]";
    }
}
