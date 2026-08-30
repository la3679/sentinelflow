/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.scoring.client;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import io.github.la3679.sentinelflow.api.resilience.CircuitBreaker;
import io.github.la3679.sentinelflow.api.resilience.FullJitterBackOff;
import io.github.la3679.sentinelflow.api.scoring.payload.ModelInfoResponse;
import io.github.la3679.sentinelflow.api.scoring.payload.ScoreRequest;
import io.github.la3679.sentinelflow.api.scoring.payload.ScoreResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Calls {@code POST /v1/score}, inside the budget ADR-0008 §3 fixed.
 *
 * <h2>Three outcomes, and they are not interchangeable</h2>
 *
 * <ul>
 *   <li>A {@link ScoreResponse}: the service answered.
 *   <li>{@link ScoringUnavailableException}: it did not, within the budget, or the breaker is open.
 *       The caller writes a <strong>degraded assessment scored by rules alone</strong> — a real
 *       answer produced in this process, which is why the ruleset lives here.
 *   <li>{@link ScoringRejectedException}: it refused. Never retried, never degraded, dead-lettered
 *       so somebody fixes the contract mismatch (ADR-0006 §4, ADR-0008 §3).
 * </ul>
 *
 * <p>Collapsing the last two into "scoring failed" is the mistake this class exists to prevent. One
 * is a dependency being briefly down, which the system is designed to survive; the other is two
 * services in one repository disagreeing about a contract, which the system should stop for.
 *
 * <h2>Why the breaker only counts unavailability</h2>
 *
 * A 4xx does not open it. The breaker's job is to stop paying the timeout against a sick service, and
 * a service returning 422 in one millisecond is not sick — it is answering, correctly, that the
 * request is wrong. Counting those would open the breaker on a defect and convert every subsequent
 * transaction into a degraded assessment, which hides the defect behind a system that still appears
 * to work.
 *
 * <h2>Latency is measured here</h2>
 *
 * {@code risk-assessed.v1} records {@code scoringLatencyMs} as "measured by the caller", and this is
 * the caller. It is wall-clock across every attempt including backoff, which is deliberately not the
 * same number as the {@code inferenceDurationMs} the service reports about itself — the difference
 * between the two is the network and the retries, and having both is what lets an operator tell a
 * slow model from a slow link without guessing.
 *
 * <p>The same measurement is recorded twice, on purpose (ADR-0016 §"Consequences"): once onto the
 * event payload, where it answers "why is <em>this</em> assessment degraded", and once into a
 * histogram, where it answers "is scoring slow today". Only the second one can be graphed.
 */
@Component
public class ScoringClient {

    private static final Logger log = LoggerFactory.getLogger(ScoringClient.class);

    private static final String SCORE_PATH = "/v1/score";
    private static final String MODEL_PATH = "/v1/model";
    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    /** What the breaker guards, for logging. Never a URL: a base URL is deployment detail. */
    static final String BREAKER_NAME = "scoring";

    /** The metric names, here rather than inline so the tests assert against the shipped strings. */
    static final String CALLS_METRIC = "sentinelflow.scoring.calls";

    static final String DURATION_METRIC = "sentinelflow.scoring.call.duration";

    /**
     * The complete outcome domain, and the whole label space of both metrics above: four values on
     * the counter, three on the timer. Closed, fixed here, and derived from nothing in a request —
     * which is ADR-0016 §2's rule.
     *
     * <p>{@code breaker_open} is a counter value with no timer beside it deliberately. A call the
     * breaker refused took no measurable time, and folding a stream of zeroes into the latency
     * histogram would make an outage look like the fastest scoring the system has ever done.
     */
    private static final String OUTCOME_SCORED = "scored";

    private static final String OUTCOME_UNAVAILABLE = "unavailable";
    private static final String OUTCOME_REJECTED = "rejected";
    private static final String OUTCOME_BREAKER_OPEN = "breaker_open";

    /**
     * Bucket boundaries, in milliseconds, for the caller-side latency histogram.
     *
     * <p>Explicit rather than Micrometer's defaults, for the reason the scoring service gives for
     * declaring its own: this call is budgeted at a two-second read timeout and expected to answer
     * in single-digit milliseconds, so the default buckets put every healthy observation in the
     * first one and measure nothing. The top of the range is past the worst case a caller can wait —
     * two retries at a two-second timeout plus backoff — so a timed-out call lands in a bucket
     * rather than only in {@code +Inf}.
     */
    private static final Duration[] LATENCY_BUCKETS = {
        Duration.ofMillis(5),
        Duration.ofMillis(10),
        Duration.ofMillis(25),
        Duration.ofMillis(50),
        Duration.ofMillis(100),
        Duration.ofMillis(250),
        Duration.ofMillis(500),
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        Duration.ofSeconds(5),
        Duration.ofSeconds(10)
    };

    private final RestClient restClient;
    private final ScoringClientProperties properties;
    private final CircuitBreaker breaker;

    // Registered once at construction rather than looked up per call. Micrometer
    // caches a builder lookup, but this is the inside of a Kafka consumer's
    // record loop and a map lookup per outcome per record is work with no
    // reader.
    private final Counter scored;
    private final Counter unavailable;
    private final Counter rejected;
    private final Counter breakerOpen;
    private final Timer scoredDuration;
    private final Timer unavailableDuration;
    private final Timer rejectedDuration;

    public ScoringClient(
            RestClient scoringRestClient,
            ScoringClientProperties properties,
            CircuitBreaker scoringCircuitBreaker,
            MeterRegistry meters) {
        this.restClient = scoringRestClient;
        this.properties = properties;
        this.breaker = scoringCircuitBreaker;

        this.scored = calls(meters, OUTCOME_SCORED);
        this.unavailable = calls(meters, OUTCOME_UNAVAILABLE);
        this.rejected = calls(meters, OUTCOME_REJECTED);
        this.breakerOpen = calls(meters, OUTCOME_BREAKER_OPEN);
        this.scoredDuration = duration(meters, OUTCOME_SCORED);
        this.unavailableDuration = duration(meters, OUTCOME_UNAVAILABLE);
        this.rejectedDuration = duration(meters, OUTCOME_REJECTED);
    }

    private static Counter calls(MeterRegistry meters, String outcome) {
        return Counter.builder(CALLS_METRIC)
                .tag("outcome", outcome)
                .description("Scoring calls this application made, by how each one ended")
                .register(meters);
    }

    private static Timer duration(MeterRegistry meters, String outcome) {
        return Timer.builder(DURATION_METRIC)
                .tag("outcome", outcome)
                .description("Wall-clock time a scoring call cost this application, including retries")
                // Buckets, never publishPercentiles: a percentile computed here
                // cannot be aggregated across instances or re-windowed, so the
                // dashboards compute theirs with histogram_quantile over these
                // (ADR-0016 section 3).
                .serviceLevelObjectives(LATENCY_BUCKETS)
                .register(meters);
    }

    /**
     * Score one transaction.
     *
     * @param request the assembled request — from {@code AccountContextAssembler}, never built here
     * @param correlationId ties this call to the transaction, the event and every log line about it
     * @return the response and the caller-measured latency
     * @throws ScoringUnavailableException the caller degrades to rules
     * @throws ScoringRejectedException the caller dead-letters
     */
    public ScoringResult score(ScoreRequest request, UUID correlationId) {
        if (!breaker.allowsRequest()) {
            // No attempt is made and no time is spent. This is the line that
            // turns a scoring outage from consumer lag proportional to traffic
            // into a stream of degraded assessments. Debug rather than warn,
            // because the warn that matters was logged when the breaker opened
            // and repeating it per record is the noise the breaker exists to
            // avoid.
            log.debug("Scoring breaker is open; degrading without an attempt");
            breakerOpen.increment();
            throw new ScoringUnavailableException("The scoring circuit breaker is open after "
                    + properties.circuitBreakerFailureThreshold() + " consecutive failures; no call was attempted");
        }

        long started = System.nanoTime();
        boolean healthy = false;
        try {
            ScoreResponse response = attemptWithRetries(request, correlationId);
            healthy = true;
            record(scored, scoredDuration, started);
            return new ScoringResult(response, elapsedMillis(started));
        } catch (ScoringRejectedException rejectedException) {
            // Answered, and answered correctly. The dependency is not sick.
            healthy = true;
            record(rejected, rejectedDuration, started);
            throw rejectedException;
        } catch (ScoringUnavailableException unavailableException) {
            // Caught only to measure it. Rethrown unchanged, because the caller
            // degrading to rules is the behaviour and this class does not decide
            // it.
            record(unavailable, unavailableDuration, started);
            throw unavailableException;
        } finally {
            // Exactly one outcome per allowed request, in a finally, because a
            // half-open probe that never reports leaves the breaker shut.
            if (healthy) {
                breaker.recordSuccess();
            } else {
                breaker.recordFailure();
            }
        }
    }

    private static void record(Counter counter, Timer timer, long startedNanos) {
        counter.increment();
        timer.record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
    }

    private ScoreResponse attemptWithRetries(ScoreRequest request, UUID correlationId) {
        int attempts = properties.maxRetries() + 1;
        BackOffExecution backOff =
                new FullJitterBackOff(properties.retryBase(), properties.retryMaxDelay(), attempts).start();

        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return post(request, correlationId);
            } catch (ScoringRejectedException rejected) {
                // Never retried. It will not become valid.
                throw rejected;
            } catch (ScoringUnavailableException unavailable) {
                last = unavailable;
                // Debug, not warn. A single failed attempt inside a budget that
                // allows three is the retry working, and logging it at warn
                // teaches an operator to ignore the level.
                log.debug("Scoring attempt {} of {} failed: {}", attempt, attempts, unavailable.getMessage());
                if (attempt == attempts) {
                    break;
                }
                sleep(backOff.nextBackOff(), unavailable);
            }
        }

        // Warn, once, when the budget is actually spent. This is the line an
        // operator sees when assessments start degrading.
        log.warn(
                "Scoring did not answer in {} attempts; the assessment will degrade to rules. Last failure: {}",
                attempts,
                last == null ? "none recorded" : last.getMessage());
        throw new ScoringUnavailableException(
                "Scoring did not answer in " + attempts + " attempts within " + properties.worstCaseCallBudget(), last);
    }

    private ScoreResponse post(ScoreRequest request, UUID correlationId) {
        try {
            ScoreResponse response = restClient
                    .post()
                    .uri(SCORE_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(CORRELATION_HEADER, correlationId.toString())
                    .body(request)
                    .exchange((httpRequest, httpResponse) -> {
                        HttpStatusCode status = httpResponse.getStatusCode();
                        if (status.is2xxSuccessful()) {
                            return httpResponse.bodyTo(ScoreResponse.class);
                        }
                        if (status.is4xxClientError()) {
                            throw new ScoringRejectedException(
                                    status.value(), "Scoring rejected the request with " + status.value());
                        }
                        throw new ScoringUnavailableException("Scoring answered " + status.value());
                    });

            if (response == null) {
                // A 2xx with no body. Not a score, and treating an absent body as
                // one would persist an assessment with a null model score and no
                // degraded flag — the third shape the schema does not have.
                throw new ScoringUnavailableException("Scoring answered 2xx with an empty body");
            }
            return response;
        } catch (ScoringRejectedException | ScoringUnavailableException known) {
            throw known;
        } catch (ResourceAccessException transport) {
            // Connect timeout, read timeout, connection refused, DNS. Every one
            // of them is "no answer", which is exactly what the degraded path is
            // for.
            throw new ScoringUnavailableException("Scoring could not be reached: " + transport.getMessage(), transport);
        } catch (RuntimeException unexpected) {
            // A malformed body, a content type nothing can read. The service is
            // answering something this build cannot use, which is closer to sick
            // than to a contract violation the caller can be blamed for.
            throw new ScoringUnavailableException(
                    "Scoring returned something this build could not read: " + unexpected.getMessage(), unexpected);
        }
    }

    private void sleep(long millis, RuntimeException cause) {
        if (millis == BackOffExecution.STOP || millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            // The listener container is shutting down. Restore the flag and stop
            // trying rather than swallowing it and starting another attempt.
            Thread.currentThread().interrupt();
            throw new ScoringUnavailableException("Interrupted while backing off before a scoring retry", cause);
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    /**
     * What model the scoring service has loaded, for the read-only screen that publishes it.
     *
     * <p><strong>It neither consults the breaker nor reports to it, and that is deliberate.</strong>
     * The breaker exists so a scoring outage costs the consumer nothing per record; it is a property
     * of the pipeline. A screen somebody refreshes must not be able to open it — that would let a
     * dashboard degrade every assessment — and must not close it either, because a successful
     * metadata read says nothing about whether inference is answering. One read, one timeout, no
     * retry.
     *
     * <p>The timeout is the request factory's, so this cannot hang a request thread on a service
     * that has stopped answering.
     *
     * @param correlationId ties this read to the request that caused it
     * @return the loaded model's metadata
     * @throws ScoringUnavailableException if the service did not answer, or answered something this
     *     build cannot read. The caller publishes the policy half alone and says the model half is
     *     unavailable — a screen that went blank while scoring restarted would be less useful than
     *     one that says so.
     */
    public ModelInfoResponse modelInfo(UUID correlationId) {
        try {
            ModelInfoResponse response = restClient
                    .get()
                    .uri(MODEL_PATH)
                    .header(CORRELATION_HEADER, correlationId.toString())
                    .exchange((httpRequest, httpResponse) -> {
                        HttpStatusCode status = httpResponse.getStatusCode();
                        if (status.is2xxSuccessful()) {
                            return httpResponse.bodyTo(ModelInfoResponse.class);
                        }
                        // 503 is what the service answers when no model is
                        // loaded, which is the ordinary case on a cold start.
                        // Not a distinct exception: to the screen and to the
                        // operator, "no model yet" and "cannot ask" are both
                        // "the model half is not available", and inventing a
                        // second failure type would put a distinction in the
                        // API that nothing acts on.
                        throw new ScoringUnavailableException("Scoring answered " + status.value() + " for /v1/model");
                    });

            if (response == null) {
                throw new ScoringUnavailableException("Scoring answered 2xx with an empty body for /v1/model");
            }
            return response;
        } catch (ScoringUnavailableException known) {
            throw known;
        } catch (ResourceAccessException transport) {
            throw new ScoringUnavailableException(
                    "Scoring could not be reached for /v1/model: " + transport.getMessage(), transport);
        } catch (RuntimeException unexpected) {
            throw new ScoringUnavailableException(
                    "Scoring returned model metadata this build could not read: " + unexpected.getMessage(),
                    unexpected);
        }
    }

    /** The breaker's state, for a health indicator and for tests. */
    public CircuitBreaker.State circuitState() {
        return breaker.state();
    }
}
