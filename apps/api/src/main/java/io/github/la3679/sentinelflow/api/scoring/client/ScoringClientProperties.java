/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.scoring.client;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import io.github.la3679.sentinelflow.api.resilience.FullJitterBackOff;

/**
 * The budget ADR-0008 §3 fixed, and the reason every number in it is small.
 *
 * <p>The scoring call happens inside a Kafka consumer whose retry blocks its partition, so
 * everything queued behind a record waits for it. The consumer's own five deliveries multiplied by a
 * generous HTTP budget is a partition stalled for minutes over one unreachable dependency — so the
 * whole budget is under ten seconds by construction, and {@link #worstCaseCallBudget()} is asserted
 * against that rather than left as an intention.
 *
 * @param baseUrl where the scoring service is. {@code http://scoring:8000} on the compose network;
 *     the published port exists so a developer can curl it and is not a deployment target.
 * @param connectTimeout 1 s. A reachable service on the compose network connects immediately, so a
 *     longer value only delays the discovery that it is not there.
 * @param readTimeout 2 s. Inference is arithmetic over a nineteen-column vector; a slow answer is a
 *     sick service, not a busy one, and waiting longer produces a stale score rather than a better
 *     one.
 * @param maxRetries 2, so three attempts. Enough for a restart or a dropped connection and no more:
 *     a dependency that fails three times in a row is not about to succeed on the fourth, and the
 *     degraded path exists precisely so it does not have to.
 * @param retryBase full-jitter base delay, 100 ms.
 * @param retryMaxDelay full-jitter ceiling, 1 s. The same distribution as everywhere else in this
 *     system (ADR-0005 §3, ADR-0006 §4): without jitter, everything that failed during an outage
 *     retries in lockstep the instant it recovers and knocks it over again.
 * @param circuitBreakerFailureThreshold 5 consecutive failures. Consecutive rather than a rate — see
 *     {@link io.github.la3679.sentinelflow.api.resilience.CircuitBreaker}.
 * @param circuitBreakerOpenDuration 30 s before one probe is let through. Long enough for a
 *     container to restart, short enough that a recovered service is scoring again within a minute.
 */
@ConfigurationProperties("sentinelflow.scoring.client")
public record ScoringClientProperties(
        URI baseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        int maxRetries,
        Duration retryBase,
        Duration retryMaxDelay,
        int circuitBreakerFailureThreshold,
        Duration circuitBreakerOpenDuration) {

    /**
     * The ceiling the whole budget has to stay under, from ADR-0008 §3.
     *
     * <p>Ten seconds is not a round number chosen for tidiness. It is the point past which the
     * consumer's five deliveries of one record hold a partition for long enough that lag becomes the
     * visible symptom rather than the scoring outage that caused it.
     */
    public static final Duration MAX_CALL_BUDGET = Duration.ofSeconds(10);

    public ScoringClientProperties {
        if (baseUrl == null || baseUrl.getHost() == null) {
            throw new IllegalArgumentException("sentinelflow.scoring.client.base-url must be an absolute URL; "
                    + "a service that discovers at the first request that it cannot reach scoring has already "
                    + "started serving traffic it cannot score");
        }
        connectTimeout = positiveOr(connectTimeout, Duration.ofSeconds(1), "connect-timeout");
        readTimeout = positiveOr(readTimeout, Duration.ofSeconds(2), "read-timeout");
        retryBase = positiveOr(retryBase, Duration.ofMillis(100), "retry-base");
        retryMaxDelay = positiveOr(retryMaxDelay, Duration.ofSeconds(1), "retry-max-delay");
        circuitBreakerOpenDuration =
                positiveOr(circuitBreakerOpenDuration, Duration.ofSeconds(30), "circuit-breaker-open-duration");

        if (maxRetries < 0) {
            throw new IllegalArgumentException(
                    "sentinelflow.scoring.client.max-retries is " + maxRetries + ", which cannot be negative");
        }
        if (circuitBreakerFailureThreshold <= 0) {
            throw new IllegalArgumentException("sentinelflow.scoring.client.circuit-breaker-failure-threshold is "
                    + circuitBreakerFailureThreshold + ", which must be positive: a threshold of zero opens the "
                    + "breaker before anything has failed and degrades every assessment forever");
        }

        Duration budget = worstCase(connectTimeout, readTimeout, maxRetries, retryBase, retryMaxDelay);
        if (budget.compareTo(MAX_CALL_BUDGET) > 0) {
            throw new IllegalArgumentException("The configured scoring budget is " + budget
                    + ", over ADR-0008 section 3's ceiling of " + MAX_CALL_BUDGET + ". This call runs inside a "
                    + "consumer that retries by blocking its partition, so the cost is paid by every record "
                    + "queued behind the one that is failing. Lower the timeouts or the retry count rather "
                    + "than raising this.");
        }
    }

    /**
     * Connect plus read on every attempt, plus the worst the jitter schedule can draw between them.
     *
     * <p>Pessimistic on the timeouts and exact on the delays. Connect and read are counted together
     * on every attempt even though one attempt cannot spend both in full — a connection that times
     * out never waits for a read — because a ceiling that assumes the best case is not a ceiling.
     * The delays come from {@link FullJitterBackOff#worstCaseTotalDelay} rather than from
     * {@code ceiling x retries}, which would overstate the first retries by a factor of five and
     * fail this check on ADR-0008 §3's own numbers.
     */
    public Duration worstCaseCallBudget() {
        return worstCase(connectTimeout, readTimeout, maxRetries, retryBase, retryMaxDelay);
    }

    private static Duration worstCase(Duration connect, Duration read, int retries, Duration base, Duration ceiling) {
        int attempts = retries + 1;
        return connect.plus(read)
                .multipliedBy(attempts)
                .plus(FullJitterBackOff.worstCaseTotalDelay(base, ceiling, attempts));
    }

    private static Duration positiveOr(Duration value, Duration fallback, String name) {
        if (value == null) {
            return fallback;
        }
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("sentinelflow.scoring.client." + name + " is " + value
                    + ", which must be positive: a zero timeout either never waits or waits forever depending "
                    + "on the client, and neither is a budget");
        }
        return value;
    }
}
