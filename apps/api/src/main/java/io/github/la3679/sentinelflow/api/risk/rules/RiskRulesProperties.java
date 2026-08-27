/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.risk.rules;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The deterministic ruleset: which indicators fire, at what threshold, and for how much.
 *
 * <p><strong>Configuration rather than constants, and validated at startup</strong> (§8.4). A weight
 * or a threshold is an operational decision that changes on a different schedule from the code that
 * evaluates it, and a ruleset compiled into a class cannot be changed without a release. What is
 * <em>not</em> configuration is which indicators exist: adding one is code, because an indicator has
 * a definition and a definition is not a number.
 *
 * <p><strong>{@code version} is not decoration.</strong> It is persisted on every assessment beside
 * the model and feature versions, because a rule score months old cannot be defended without knowing
 * which weights produced it. Change a weight or a threshold and this moves — the record of what ran
 * is the whole point, and a stale version is worse than none.
 *
 * <p><strong>Invalid values fail the context rather than falling back.</strong> This differs from
 * {@link io.github.la3679.sentinelflow.api.scoring.ScoringContextProperties}, which clamps, and the
 * difference is deliberate: a clamped lookback window still produces a defensible context, whereas a
 * negative weight produces a score that is silently wrong in a direction nobody chose. A service
 * that will not start is a problem an operator can see.
 */
@ConfigurationProperties("sentinelflow.risk.rules")
public record RiskRulesProperties(
        String version,
        int velocity5mCount,
        BigDecimal amountRatio,
        BigDecimal balanceDrainRatio,
        int distinctMerchants1hCount,
        Map<RuleCode, BigDecimal> weights) {

    /** The contract's range for a score. Both ends, because a rule score shares it with the model's. */
    public static final BigDecimal SCORE_MIN = BigDecimal.ZERO;

    public static final BigDecimal SCORE_MAX = new BigDecimal("100");

    public RiskRulesProperties {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("sentinelflow.risk.rules.version is required: an assessment that "
                    + "cannot name the ruleset that produced it cannot be defended later");
        }
        requirePositive("velocity-5m-count", velocity5mCount);
        requirePositive("distinct-merchants-1h-count", distinctMerchants1hCount);
        requirePositive("amount-ratio", amountRatio);
        requirePositive("balance-drain-ratio", balanceDrainRatio);

        Map<RuleCode, BigDecimal> resolved = new EnumMap<>(RuleCode.class);
        resolved.putAll(weights == null ? Map.of() : weights);
        for (RuleCode code : RuleCode.values()) {
            BigDecimal weight = resolved.get(code);
            if (weight == null) {
                throw new IllegalArgumentException("sentinelflow.risk.rules.weights is missing " + code
                        + ". Every rule carries a weight, and an absent one defaulting to zero would "
                        + "retire a rule by omission — which is a decision, not a typo's consequence.");
            }
            if (weight.signum() < 0 || weight.compareTo(SCORE_MAX) > 0) {
                throw new IllegalArgumentException("sentinelflow.risk.rules.weights." + code + " is " + weight
                        + ", outside 0 to 100. A negative weight makes a suspicious indicator lower the "
                        + "score, and one above the scale makes every other rule irrelevant.");
            }
        }
        weights = Map.copyOf(resolved);
    }

    /** The weight one rule contributes when it fires. Never null: the constructor rejects an absent one. */
    public BigDecimal weightOf(RuleCode code) {
        return weights.get(code);
    }

    /**
     * The sum of every weight, which is deliberately allowed to exceed 100.
     *
     * <p>Reported rather than enforced. The score is clipped at 100, so a ceiling above the scale
     * means a transaction that trips several strong indicators reaches the top of it — which is the
     * behaviour wanted, since "as suspicious as this ruleset can say" is a real answer. Normalising
     * instead would make each individual weight a fraction of a total that changes whenever a rule is
     * added, so today's weights would silently mean something different tomorrow.
     */
    public BigDecimal totalWeight() {
        return weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static void requirePositive(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("sentinelflow.risk.rules." + name + " is " + value
                    + ", which must be positive: a count threshold of zero fires on every transaction");
        }
    }

    private static void requirePositive(String name, BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException("sentinelflow.risk.rules." + name + " is " + value
                    + ", which must be positive: a ratio threshold of zero or less fires on every transaction");
        }
    }
}
