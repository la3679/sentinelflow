/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.risk.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Validation, which is the only behaviour this record has, and the reason it fails rather than
 * falls back.
 *
 * <p>{@code ScoringContextProperties} clamps a bad value because a clamped lookback window still
 * produces a defensible context. This does not, because a negative weight produces a score that is
 * silently wrong in a direction nobody chose — and a service that will not start is a problem an
 * operator can see, where a quietly inverted rule is one nobody finds until an audit.
 */
class RiskRulesPropertiesTests {

    /** The contract's shape for a reason code, from {@code contracts/schemas/common.v1.json}. */
    private static final Pattern REASON_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{2,63}$");

    @Test
    @DisplayName("every rule code satisfies the contract's pattern")
    void codesMatchTheContract() {
        for (RuleCode code : RuleCode.values()) {
            assertThat(code.name())
                    .as("a code that fails validation is one the assessment cannot carry")
                    .matches(REASON_CODE);
        }
    }

    @Test
    @DisplayName("a complete configuration is accepted and reports its own ceiling")
    void acceptsACompleteConfiguration() {
        RiskRulesProperties properties = valid();

        assertThat(properties.version()).isEqualTo("1.0.0");
        assertThat(properties.totalWeight())
                .as("the weights are allowed above the scale on purpose, so the clip is real")
                .isEqualByComparingTo(new BigDecimal("110"));
    }

    @Test
    @DisplayName("a missing weight is rejected, because retiring a rule is a decision")
    void rejectsAMissingWeight() {
        Map<RuleCode, BigDecimal> incomplete = new EnumMap<>(weights());
        incomplete.remove(RuleCode.NEW_DEVICE);

        assertThatThrownBy(() -> new RiskRulesProperties(
                        "1.0.0", 4, new BigDecimal("5.0"), new BigDecimal("0.5"), 4, incomplete))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NEW_DEVICE");
    }

    @Test
    @DisplayName("a negative weight is rejected, because it would make a suspicious signal exculpatory")
    void rejectsANegativeWeight() {
        Map<RuleCode, BigDecimal> inverted = new EnumMap<>(weights());
        inverted.put(RuleCode.OFF_HOURS, new BigDecimal("-10"));

        assertThatThrownBy(() ->
                        new RiskRulesProperties("1.0.0", 4, new BigDecimal("5.0"), new BigDecimal("0.5"), 4, inverted))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OFF_HOURS");
    }

    @Test
    @DisplayName("a weight above the scale is rejected, because it makes every other rule irrelevant")
    void rejectsAnOversizedWeight() {
        Map<RuleCode, BigDecimal> dominant = new EnumMap<>(weights());
        dominant.put(RuleCode.OFF_HOURS, new BigDecimal("500"));

        assertThatThrownBy(() ->
                        new RiskRulesProperties("1.0.0", 4, new BigDecimal("5.0"), new BigDecimal("0.5"), 4, dominant))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a threshold of zero is rejected, because it fires on every transaction")
    void rejectsAZeroThreshold() {
        assertThatThrownBy(() ->
                        new RiskRulesProperties("1.0.0", 0, new BigDecimal("5.0"), new BigDecimal("0.5"), 4, weights()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("velocity-5m-count");

        assertThatThrownBy(
                        () -> new RiskRulesProperties("1.0.0", 4, BigDecimal.ZERO, new BigDecimal("0.5"), 4, weights()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount-ratio");
    }

    @Test
    @DisplayName("a blank version is rejected, because an assessment must name what produced it")
    void rejectsABlankVersion() {
        assertThatThrownBy(() ->
                        new RiskRulesProperties(" ", 4, new BigDecimal("5.0"), new BigDecimal("0.5"), 4, weights()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
    }

    private static RiskRulesProperties valid() {
        return new RiskRulesProperties("1.0.0", 4, new BigDecimal("5.0"), new BigDecimal("0.5"), 4, weights());
    }

    private static Map<RuleCode, BigDecimal> weights() {
        Map<RuleCode, BigDecimal> weights = new EnumMap<>(RuleCode.class);
        weights.put(RuleCode.VELOCITY_5M_HIGH, new BigDecimal("25"));
        weights.put(RuleCode.AMOUNT_RATIO_HIGH, new BigDecimal("20"));
        weights.put(RuleCode.NEW_DEVICE, new BigDecimal("15"));
        weights.put(RuleCode.COUNTRY_CHANGE, new BigDecimal("15"));
        weights.put(RuleCode.BALANCE_DRAIN_HIGH, new BigDecimal("15"));
        weights.put(RuleCode.OFF_HOURS, new BigDecimal("10"));
        weights.put(RuleCode.DISTINCT_MERCHANTS_1H_HIGH, new BigDecimal("10"));
        return weights;
    }
}
