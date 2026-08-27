/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.scoring;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How much account history crosses the scoring boundary.
 *
 * <p>Both values are stated on every request as {@code lookbackWindowSeconds} and, by implication,
 * {@code truncated} — so the scoring service is never left inferring how much history it was given.
 * That is the whole reason they are configuration rather than constants: a deployment that shortens
 * the window changes what several features mean, and the service has to be told rather than left to
 * assume the default.
 *
 * @param lookbackWindow how far back {@link AccountContextAssembler} reaches. The default is 24
 *     hours because that is the longest window any current feature is defined over — the 24-hour
 *     amount sum. Shortening it does not make that feature smaller, it makes it wrong, and the
 *     service warns rather than answering confidently.
 * @param maxRecentTransactions the cap on {@code recentTransactions}. Bounded on purpose: a request
 *     that grew with an account's history would be a denial-of-service primitive against the
 *     service, the network and this application's own heap. The contract declares
 *     {@code maxItems: 200}, so a larger value here would produce requests the service is required
 *     to reject — which is why it is clamped rather than trusted.
 */
@ConfigurationProperties("sentinelflow.scoring.context")
public record ScoringContextProperties(Duration lookbackWindow, int maxRecentTransactions) {

    /**
     * The contract's {@code maxItems} for {@code recentTransactions}, and therefore a ceiling rather
     * than a preference. Kept in sync with {@code contracts/openapi/sentinelflow-scoring.yaml};
     * {@code ScoringPayloadContractTests} asserts they have not drifted.
     */
    public static final int CONTRACT_MAX_RECENT_TRANSACTIONS = 200;

    public ScoringContextProperties {
        lookbackWindow = lookbackWindow == null || lookbackWindow.isZero() || lookbackWindow.isNegative()
                ? Duration.ofHours(24)
                : lookbackWindow;
        maxRecentTransactions = maxRecentTransactions <= 0
                ? CONTRACT_MAX_RECENT_TRANSACTIONS
                : Math.min(maxRecentTransactions, CONTRACT_MAX_RECENT_TRANSACTIONS);
    }
}
