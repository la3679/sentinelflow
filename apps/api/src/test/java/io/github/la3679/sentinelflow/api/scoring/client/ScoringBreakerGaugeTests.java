/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.scoring.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.la3679.sentinelflow.api.resilience.CircuitBreaker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The breaker's state as a scrape sees it.
 *
 * <p>Against the bean method rather than against a hand-built gauge, because the thing worth
 * asserting is that <em>the wiring that ships</em> publishes three series that follow the breaker.
 * A test that registered its own gauge would pass while the configuration registered none.
 */
class ScoringBreakerGaugeTests {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    @DisplayName("one series per state, and exactly one of them reads 1")
    void publishesOneSeriesPerState() {
        CircuitBreaker breaker = new ScoringClientConfiguration().scoringCircuitBreaker(properties(), meters);

        assertThat(meters.find(ScoringClientConfiguration.BREAKER_STATE_METRIC).gauges())
                .as("a state encoded as a number would need its legend repeated in every panel "
                        + "and every alert rule")
                .hasSize(CircuitBreaker.State.values().length);

        assertThat(value("CLOSED")).isEqualTo(1);
        assertThat(value("OPEN")).isZero();
        assertThat(value("HALF_OPEN")).isZero();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("the gauges follow the breaker rather than a value copied when it was registered")
    void followsTheBreaker() {
        CircuitBreaker breaker = new ScoringClientConfiguration().scoringCircuitBreaker(properties(), meters);

        for (int failure = 0; failure < 5; failure++) {
            breaker.recordFailure();
        }

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(value("OPEN")).isEqualTo(1);
        assertThat(value("CLOSED")).isZero();

        breaker.recordSuccess();

        assertThat(value("CLOSED")).isEqualTo(1);
        assertThat(value("OPEN")).isZero();
    }

    @Test
    @DisplayName("the only labels are the breaker's name and the state, both fixed in code")
    void keepsTheLabelSpaceClosed() {
        new ScoringClientConfiguration().scoringCircuitBreaker(properties(), meters);

        assertThat(meters.find(ScoringClientConfiguration.BREAKER_STATE_METRIC).gauges())
                .allSatisfy(gauge -> assertThat(gauge.getId().getTags())
                        .as("ADR-0016 section 2, and the breaker's own rule that its name is never "
                                + "a URL - a base URL is deployment detail")
                        .allSatisfy(tag -> assertThat(tag.getKey()).isIn("breaker", "state")));
    }

    private double value(String state) {
        Gauge gauge = meters.find(ScoringClientConfiguration.BREAKER_STATE_METRIC)
                .tag("state", state)
                .gauge();
        assertThat(gauge).as("no series for state %s", state).isNotNull();
        return gauge.value();
    }

    private static ScoringClientProperties properties() {
        return new ScoringClientProperties(
                URI.create("http://127.0.0.1:8000"),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                2,
                Duration.ofMillis(100),
                Duration.ofSeconds(1),
                5,
                Duration.ofSeconds(30));
    }
}
