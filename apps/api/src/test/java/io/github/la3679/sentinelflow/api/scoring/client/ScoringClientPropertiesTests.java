/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.scoring.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The budget check, which is the only behaviour this record has and the one that matters.
 *
 * <p>ADR-0008 §3 says the whole call budget is under ten seconds "by construction". A sentence in a
 * document is not a construction. This is.
 */
class ScoringClientPropertiesTests {

    private static final URI BASE = URI.create("http://scoring:8000");

    @Test
    @DisplayName("ADR-0008 section 3's own numbers are accepted, and land under the ceiling")
    void theAdrsNumbersFit() {
        ScoringClientProperties properties = shipped();

        assertThat(properties.worstCaseCallBudget())
                .as("connect 1s + read 2s, three attempts, plus the jitter the schedule can actually "
                        + "draw across two retries")
                .isLessThan(ScoringClientProperties.MAX_CALL_BUDGET);
    }

    @Test
    @DisplayName("the jitter is counted from the real schedule, not from the ceiling")
    void theJitterIsTheScheduleS() {
        // The ceiling is 1s and there are two retries, so a `ceiling x retries`
        // estimate would add 2s and push the total to 11s — over the ADR's own
        // limit, on the ADR's own numbers. The windows are 200ms and 400ms.
        assertThat(shipped().worstCaseCallBudget()).isBetween(Duration.ofMillis(9_000), Duration.ofMillis(9_600));
    }

    @Test
    @DisplayName("a budget over the ceiling is refused with the reason it exists")
    void refusesAnOversizedBudget() {
        assertThatThrownBy(() -> properties(Duration.ofSeconds(5), Duration.ofSeconds(10), 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blocking its partition");
    }

    @Test
    @DisplayName("more retries can push a legal set of timeouts over the ceiling")
    void retriesCountTowardsTheBudget() {
        assertThatCode(() -> properties(Duration.ofSeconds(1), Duration.ofSeconds(2), 2))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> properties(Duration.ofSeconds(1), Duration.ofSeconds(2), 3))
                .as("four attempts at three seconds each is twelve, whatever the timeouts look like " + "individually")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an absent base URL is refused at startup rather than at the first transaction")
    void refusesAnAbsentBaseUrl() {
        assertThatThrownBy(() -> new ScoringClientProperties(
                        null,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2),
                        2,
                        Duration.ofMillis(100),
                        Duration.ofSeconds(1),
                        5,
                        Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base-url");
    }

    @Test
    @DisplayName("a zero timeout is refused, because it is not a budget")
    void refusesAZeroTimeout() {
        assertThatThrownBy(() -> properties(Duration.ZERO, Duration.ofSeconds(2), 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connect-timeout");
    }

    @Test
    @DisplayName("a breaker threshold of zero is refused, because it degrades everything forever")
    void refusesAZeroBreakerThreshold() {
        assertThatThrownBy(() -> new ScoringClientProperties(
                        BASE,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2),
                        2,
                        Duration.ofMillis(100),
                        Duration.ofSeconds(1),
                        0,
                        Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("degrades every assessment forever");
    }

    @Test
    @DisplayName("absent durations fall back to the ADR's values rather than to zero")
    void absentDurationsFallBack() {
        ScoringClientProperties properties = new ScoringClientProperties(BASE, null, null, 2, null, null, 5, null);

        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.retryBase()).isEqualTo(Duration.ofMillis(100));
        assertThat(properties.retryMaxDelay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.circuitBreakerOpenDuration()).isEqualTo(Duration.ofSeconds(30));
    }

    private static ScoringClientProperties shipped() {
        return properties(Duration.ofSeconds(1), Duration.ofSeconds(2), 2);
    }

    private static ScoringClientProperties properties(Duration connect, Duration read, int retries) {
        return new ScoringClientProperties(
                BASE, connect, read, retries, Duration.ofMillis(100), Duration.ofSeconds(1), 5, Duration.ofSeconds(30));
    }
}
