/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.risk;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import io.github.la3679.sentinelflow.api.domain.AlertPriority;
import io.github.la3679.sentinelflow.api.domain.RiskBand;

/**
 * The alerting policy: how a rule score and a model score become one number, and what bands it.
 *
 * <p>ADR-0011. Configuration rather than constants because it is a <strong>business decision on a
 * different schedule from the model</strong> (ADR-0008 §4) — a threshold that shipped inside a model
 * artifact could not be changed without retraining, and "which policy produced this alert" would
 * have no answer independent of "which model".
 *
 * <p><strong>{@code version} is persisted on every assessment.</strong> Change a weight or a
 * threshold and it moves. An assessment that cannot name the policy that produced it cannot be
 * defended months later, and the whole reason this object is versioned separately is that it will
 * move more often than the model does.
 *
 * <p><strong>Alerting lives here too, on the same version.</strong> ADR-0008 §4 gives this service
 * the threshold and calls it "the alerting policy applied to a final score at runtime"; deciding
 * which bands are worth a human's time is that policy, not a second one. Putting it in its own
 * versioned object would let a band table and an alerting rule move independently while both claimed
 * to be "the policy", and an alert months old could then name a version that does not describe the
 * decision that raised it.
 *
 * @param version identifies this whole object — the weight, the thresholds and the alerting rule
 *     together
 * @param modelWeight how much of the combined score is the model's. 0.6 by default; the rest is the
 *     rules'. The result is floored by the rule score, so this weights corroboration rather than
 *     letting a confident model overrule a transparent indicator (ADR-0011 §1).
 * @param bandLowerBounds inclusive lower bound per band, ascending, starting at zero
 * @param alertFromBand the least severe band that opens an alert. Every band at or above it alerts
 *     and every band below it does not, because an alerting rule that was not monotone in severity
 *     would be one nobody could hold in their head.
 * @param priorityByBand what an alert's queue priority is, per alerting band. Separate from the band
 *     because the band describes the score and the priority describes the queue — an operations team
 *     must be able to reorder its own work without anyone re-deciding what the score meant.
 */
@ConfigurationProperties("sentinelflow.risk.policy")
public record RiskPolicyProperties(
        String version,
        BigDecimal modelWeight,
        Map<RiskBand, BigDecimal> bandLowerBounds,
        RiskBand alertFromBand,
        Map<RiskBand, AlertPriority> priorityByBand) {

    /** The contract's scale, shared by the rule score, the model score and the final score. */
    public static final BigDecimal SCORE_MIN = BigDecimal.ZERO;

    public static final BigDecimal SCORE_MAX = new BigDecimal("100");

    /** Scale 2, matching {@code NUMERIC(5,2)}: a value the column would round is one two readers disagree about. */
    public static final int SCORE_SCALE = 2;

    public RiskPolicyProperties {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("sentinelflow.risk.policy.version is required: an assessment that "
                    + "cannot name the policy that produced it cannot be defended later");
        }
        if (modelWeight == null || modelWeight.signum() < 0 || modelWeight.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("sentinelflow.risk.policy.model-weight is " + modelWeight
                    + ", which must be between 0 and 1. Outside that range the combination is no longer a "
                    + "weighted mean and the final score leaves the contract's scale.");
        }
        bandLowerBounds = validatedBands(bandLowerBounds);

        if (alertFromBand == null) {
            throw new IllegalArgumentException("sentinelflow.risk.policy.alert-from-band is required: a policy "
                    + "that does not say which bands are worth a human's time cannot raise an alert anybody "
                    + "can defend, and defaulting it here would be this class deciding a business question.");
        }
        priorityByBand = validatedPriorities(alertFromBand, priorityByBand);
    }

    /**
     * Whether a band opens an alert.
     *
     * <p>By ordinal, which is safe because {@link RiskBand} is declared least to most severe and its
     * own Javadoc says so. Comparing bands rather than scores is deliberate: the band is what an
     * assessment persists and what an analyst is shown, so an alert raised on a score the band table
     * would have placed differently is an alert nobody can reconcile with the row beside it.
     */
    public boolean raisesAlert(RiskBand band) {
        return band.ordinal() >= alertFromBand.ordinal();
    }

    /**
     * The queue priority for an alerting band.
     *
     * @throws IllegalArgumentException if the band does not alert. Not a default: asking for the
     *     priority of an alert that should not exist is a caller defect, and answering it with
     *     {@code LOW} would open one.
     */
    public AlertPriority priorityFor(RiskBand band) {
        AlertPriority priority = priorityByBand.get(band);
        if (priority == null) {
            throw new IllegalArgumentException(
                    "No alert priority is configured for " + band + ", which does not raise an alert under policy "
                            + version + ". Alerts start at " + alertFromBand + ".");
        }
        return priority;
    }

    /**
     * The band a final score falls into.
     *
     * <p>Walked from the most severe down, so a score sits in the highest band whose lower bound it
     * reaches. Ascending order and full coverage are guaranteed by the constructor, which is why this
     * has no fallback branch to be wrong in.
     */
    public RiskBand bandFor(BigDecimal finalScore) {
        RiskBand[] bands = RiskBand.values();
        for (int index = bands.length - 1; index >= 0; index--) {
            if (finalScore.compareTo(bandLowerBounds.get(bands[index])) >= 0) {
                return bands[index];
            }
        }
        // Unreachable: LOW is pinned to zero by the constructor and the score
        // cannot be negative. Throwing rather than returning LOW, because a
        // reachable "impossible" branch that quietly returns the mildest band is
        // how a CRITICAL transaction becomes a LOW one.
        throw new IllegalStateException(
                "No band covers " + finalScore + ", which the validated bounds make impossible");
    }

    /**
     * The combination ADR-0011 §1 fixed: a weighted mean, floored by the rule score.
     *
     * <p>The floor is the part worth reading twice. Without it, a model scoring zero on a transaction
     * that tripped three rules drags the final score to 40% of what the rules said — and a
     * transparent indicator an analyst would act on is diluted by a number they cannot inspect. It
     * costs nothing when the two agree.
     */
    public BigDecimal combine(BigDecimal ruleScore, BigDecimal modelScore) {
        BigDecimal weighted =
                modelScore.multiply(modelWeight).add(ruleScore.multiply(BigDecimal.ONE.subtract(modelWeight)));
        return clip(weighted.max(ruleScore));
    }

    /** The degraded case: the rule score unchanged, never scaled up to stand in for a missing model. */
    public BigDecimal combine(BigDecimal ruleScore) {
        return clip(ruleScore);
    }

    /**
     * A score as this contract represents one: within the scale, at the scale the column stores.
     *
     * <p>Applied to the model score before it is persisted, rather than left to the database. The
     * contract bounds {@code modelScore} to 0-to-100 and the scoring service's own response schema
     * enforces it, so the clipping is belt and braces — but the <em>scale</em> is not. A model score
     * of {@code 78.5678} is a value {@code NUMERIC(5,2)} would round on the way in, so the row and
     * the {@code risk.assessed} event built from the in-memory entity would disagree about the same
     * number. Rounding here means both readers see what the column holds.
     */
    public BigDecimal onContractScale(BigDecimal score) {
        return clip(score);
    }

    private static BigDecimal clip(BigDecimal score) {
        return score.max(SCORE_MIN).min(SCORE_MAX).setScale(SCORE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Every alerting band has a priority, and no non-alerting band has one.
     *
     * <p>Both halves are refusals rather than corrections. A missing priority would surface as an
     * exception on the first alert of that band, which is the worst moment to discover it. A
     * priority configured for a band that does not alert is dead configuration that reads as though
     * it does something — and the most likely reason for it to exist is that somebody changed
     * {@code alertFromBand} and did not finish.
     */
    private static Map<RiskBand, AlertPriority> validatedPriorities(
            RiskBand alertFromBand, Map<RiskBand, AlertPriority> configured) {
        Map<RiskBand, AlertPriority> priorities = new EnumMap<>(RiskBand.class);
        priorities.putAll(configured == null ? Map.of() : configured);

        List<String> problems = new ArrayList<>();
        for (RiskBand band : RiskBand.values()) {
            boolean alerts = band.ordinal() >= alertFromBand.ordinal();
            boolean configuredForBand = priorities.containsKey(band);
            if (alerts && !configuredForBand) {
                problems.add(band + " raises an alert and has no priority");
            }
            if (!alerts && configuredForBand) {
                problems.add(band + " has a priority and does not raise an alert");
            }
        }

        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("sentinelflow.risk.policy.priority-by-band does not match "
                    + "alert-from-band = " + alertFromBand + ": " + String.join("; ", problems)
                    + ". Refused rather than defaulted, because a priority this class chose would put alerts "
                    + "in an order nobody decided.");
        }
        return Map.copyOf(priorities);
    }

    private static Map<RiskBand, BigDecimal> validatedBands(Map<RiskBand, BigDecimal> configured) {
        Map<RiskBand, BigDecimal> bounds = new EnumMap<>(RiskBand.class);
        bounds.putAll(configured == null ? Map.of() : configured);

        List<String> problems = new ArrayList<>();
        BigDecimal previous = null;
        for (RiskBand band : RiskBand.values()) {
            BigDecimal bound = bounds.get(band);
            if (bound == null) {
                problems.add(band + " has no lower bound");
                continue;
            }
            if (bound.compareTo(SCORE_MIN) < 0 || bound.compareTo(SCORE_MAX) > 0) {
                problems.add(band + " is " + bound + ", outside the 0-to-100 scale");
            }
            if (previous != null && bound.compareTo(previous) <= 0) {
                problems.add(band + " is " + bound + ", which does not exceed the band below it");
            }
            previous = bound;
        }
        BigDecimal lowest = bounds.get(RiskBand.values()[0]);
        if (lowest != null && lowest.compareTo(SCORE_MIN) != 0) {
            problems.add(RiskBand.values()[0] + " must start at 0, or scores below it fall in no band at all");
        }

        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("sentinelflow.risk.policy.band-lower-bounds is not a usable band "
                    + "table: " + String.join("; ", problems)
                    + ". Refused rather than clamped, because a table that silently reorders itself produces "
                    + "assessments nobody can explain.");
        }
        return Map.copyOf(bounds);
    }
}
