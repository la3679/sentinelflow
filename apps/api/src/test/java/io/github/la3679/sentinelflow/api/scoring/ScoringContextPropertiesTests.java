/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The clamping, which is the only behaviour this record has.
 *
 * <p>It matters because the cap is a contract term rather than a tuning knob: the scoring service
 * declares {@code maxItems: 200} and is required to reject anything larger, so a deployment that set
 * 500 would not get a bigger context — it would get a 422 on every score, and the first symptom
 * would be every transaction degrading at once with nothing in the API's own logs to explain it.
 */
class ScoringContextPropertiesTests {

    @Test
    @DisplayName("a cap above the contract's maxItems is clamped, not honoured")
    void clampsToTheContractCeiling() {
        ScoringContextProperties properties = new ScoringContextProperties(Duration.ofHours(24), 500);

        assertThat(properties.maxRecentTransactions())
                .as("honouring it would build requests the scoring service must reject, which is a "
                        + "misconfiguration that presents as a scoring outage")
                .isEqualTo(ScoringContextProperties.CONTRACT_MAX_RECENT_TRANSACTIONS);
    }

    @Test
    @DisplayName("a smaller cap is honoured, because a deployment may legitimately want less")
    void honoursASmallerCap() {
        assertThat(new ScoringContextProperties(Duration.ofHours(24), 25).maxRecentTransactions())
                .isEqualTo(25);
    }

    @Test
    @DisplayName("absent or nonsensical values fall back rather than producing an unusable context")
    void fallsBackOnNonsense() {
        assertThat(new ScoringContextProperties(null, 0).lookbackWindow()).isEqualTo(Duration.ofHours(24));
        assertThat(new ScoringContextProperties(null, 0).maxRecentTransactions())
                .isEqualTo(ScoringContextProperties.CONTRACT_MAX_RECENT_TRANSACTIONS);

        assertThat(new ScoringContextProperties(Duration.ZERO, -1).lookbackWindow())
                .as("a zero window would send an empty context on every request and every velocity "
                        + "feature would read as a quiet account, which looks like a working system")
                .isEqualTo(Duration.ofHours(24));
        assertThat(new ScoringContextProperties(Duration.ofSeconds(-5), -1).lookbackWindow())
                .as("a negative window would make notBefore later than before, and the query would "
                        + "return nothing for every account without erroring")
                .isEqualTo(Duration.ofHours(24));
    }

    @Test
    @DisplayName("a deliberate window is left alone")
    void honoursADeliberateWindow() {
        assertThat(new ScoringContextProperties(Duration.ofHours(1), 200).lookbackWindow())
                .isEqualTo(Duration.ofHours(1));
    }
}
