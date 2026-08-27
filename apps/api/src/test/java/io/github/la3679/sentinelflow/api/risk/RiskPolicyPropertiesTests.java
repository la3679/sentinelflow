/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.la3679.sentinelflow.api.domain.RiskBand;

/**
 * ADR-0011's arithmetic, and the band table's refusal to be nonsense.
 *
 * <p>The combination is one expression, so it is worth the handful of cases that pin what each term
 * is for: the weighted part is why the model is here, and the floor is why the rules are.
 */
class RiskPolicyPropertiesTests {

    private final RiskPolicyProperties policy = policy(new BigDecimal("0.6"));

    // ----------------------------------------------------------------------- //
    // Combination
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("a confident model raises a quiet rule score")
    void theModelCanRaiseTheScore() {
        // 0.6 x 90 + 0.4 x 10 = 58, above the rule floor of 10.
        assertThat(policy.combine(new BigDecimal("10"), new BigDecimal("90")))
                .as("the model finds shapes the rules miss; a combination that ignored it would make "
                        + "the model an expensive decoration")
                .isEqualByComparingTo("58.00");
    }

    @Test
    @DisplayName("a model that finds nothing cannot dilute a rule that fired")
    void theRuleScoreIsAFloor() {
        // The weighted mean would be 0.4 x 75 = 30.
        assertThat(policy.combine(new BigDecimal("75"), BigDecimal.ZERO))
                .as("a transparent indicator an analyst would act on must not be diluted by a number "
                        + "they cannot inspect")
                .isEqualByComparingTo("75.00");
    }

    @Test
    @DisplayName("the model can only ever raise a score, never lower one")
    void theModelIsOneDirectional() {
        // The floor makes this true by construction, and it is worth an
        // assertion because it is the whole shape of the policy: the rules set
        // the floor and the model escalates above it. A model agreeing at the
        // rule score changes nothing, which is correct — it has added no
        // information the rules did not already have.
        BigDecimal ruleOnly = policy.combine(new BigDecimal("60"), BigDecimal.ZERO);
        BigDecimal agreeing = policy.combine(new BigDecimal("60"), new BigDecimal("60"));
        BigDecimal escalating = policy.combine(new BigDecimal("60"), new BigDecimal("95"));

        assertThat(ruleOnly).isEqualByComparingTo("60.00");
        assertThat(agreeing)
                .as("an equal model score adds nothing over the floor, and pretending otherwise "
                        + "would make the same evidence count twice")
                .isEqualByComparingTo(ruleOnly);
        assertThat(escalating)
                .as("above the floor the weight applies, so a more confident model moves the score "
                        + "proportionally rather than wholesale")
                .isEqualByComparingTo("81.00")
                .isGreaterThan(agreeing);
    }

    @Test
    @DisplayName("a model above the floor moves the score proportionally, not to its own value")
    void theWeightAppliesAboveTheFloor() {
        // max(rule, model) would give 95 here. The weight is what makes it 81:
        // the model leads, and the rules still have a say in how far.
        assertThat(policy.combine(new BigDecimal("60"), new BigDecimal("95"))).isEqualByComparingTo("81.00");
    }

    @Test
    @DisplayName("a degraded assessment is the rule score unchanged, never scaled up")
    void degradedIsTheRuleScore() {
        assertThat(policy.combine(new BigDecimal("40")))
                .as("scaling would be inventing the model's opinion, and the number would then be "
                        + "neither the rules' answer nor anybody's")
                .isEqualByComparingTo("40.00");
    }

    @Test
    @DisplayName("the result stays on the contract's scale and at the column's precision")
    void staysOnTheScale() {
        assertThat(policy.combine(new BigDecimal("100"), new BigDecimal("100"))).isEqualByComparingTo("100.00");
        assertThat(policy.combine(BigDecimal.ZERO, BigDecimal.ZERO).scale())
                .as("NUMERIC(5,2): a value the column would round is one two readers disagree about")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a weight of one is the model alone, still floored by the rules")
    void aWeightOfOne() {
        RiskPolicyProperties modelOnly = policy(BigDecimal.ONE);

        assertThat(modelOnly.combine(new BigDecimal("10"), new BigDecimal("80")))
                .isEqualByComparingTo("80.00");
        assertThat(modelOnly.combine(new BigDecimal("80"), new BigDecimal("10")))
                .isEqualByComparingTo("80.00");
    }

    // ----------------------------------------------------------------------- //
    // Bands
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("a score sits in the highest band whose lower bound it reaches")
    void bandsAreInclusiveLowerBounds() {
        assertThat(policy.bandFor(new BigDecimal("0"))).isEqualTo(RiskBand.LOW);
        assertThat(policy.bandFor(new BigDecimal("39.99"))).isEqualTo(RiskBand.LOW);
        assertThat(policy.bandFor(new BigDecimal("40"))).isEqualTo(RiskBand.MEDIUM);
        assertThat(policy.bandFor(new BigDecimal("69.99"))).isEqualTo(RiskBand.MEDIUM);
        assertThat(policy.bandFor(new BigDecimal("70"))).isEqualTo(RiskBand.HIGH);
        assertThat(policy.bandFor(new BigDecimal("89.99"))).isEqualTo(RiskBand.HIGH);
        assertThat(policy.bandFor(new BigDecimal("90"))).isEqualTo(RiskBand.CRITICAL);
        assertThat(policy.bandFor(new BigDecimal("100"))).isEqualTo(RiskBand.CRITICAL);
    }

    @Test
    @DisplayName("an inverted table is refused rather than reordered")
    void refusesAnInvertedTable() {
        Map<RiskBand, BigDecimal> inverted = bounds();
        inverted.put(RiskBand.HIGH, new BigDecimal("30"));

        assertThatThrownBy(() -> new RiskPolicyProperties("1.0.0", new BigDecimal("0.6"), inverted))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not exceed the band below it");
    }

    @Test
    @DisplayName("a table that does not start at zero is refused, because scores would fall in no band")
    void refusesATableWithAGapAtTheBottom() {
        Map<RiskBand, BigDecimal> raised = bounds();
        raised.put(RiskBand.LOW, new BigDecimal("5"));

        assertThatThrownBy(() -> new RiskPolicyProperties("1.0.0", new BigDecimal("0.6"), raised))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must start at 0");
    }

    @Test
    @DisplayName("a missing band is refused, because every band has to be reachable")
    void refusesAMissingBand() {
        Map<RiskBand, BigDecimal> incomplete = bounds();
        incomplete.remove(RiskBand.CRITICAL);

        assertThatThrownBy(() -> new RiskPolicyProperties("1.0.0", new BigDecimal("0.6"), incomplete))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CRITICAL has no lower bound");
    }

    @Test
    @DisplayName("a weight outside 0 to 1 is refused")
    void refusesAWeightOffTheScale() {
        assertThatThrownBy(() -> new RiskPolicyProperties("1.0.0", new BigDecimal("1.5"), bounds()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0 and 1");
    }

    @Test
    @DisplayName("a blank version is refused, because an assessment must name what produced it")
    void refusesABlankVersion() {
        assertThatThrownBy(() -> new RiskPolicyProperties(" ", new BigDecimal("0.6"), bounds()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
    }

    private static RiskPolicyProperties policy(BigDecimal modelWeight) {
        return new RiskPolicyProperties("1.0.0", modelWeight, bounds());
    }

    private static Map<RiskBand, BigDecimal> bounds() {
        Map<RiskBand, BigDecimal> bounds = new EnumMap<>(RiskBand.class);
        bounds.put(RiskBand.LOW, new BigDecimal("0"));
        bounds.put(RiskBand.MEDIUM, new BigDecimal("40"));
        bounds.put(RiskBand.HIGH, new BigDecimal("70"));
        bounds.put(RiskBand.CRITICAL, new BigDecimal("90"));
        return bounds;
    }
}
