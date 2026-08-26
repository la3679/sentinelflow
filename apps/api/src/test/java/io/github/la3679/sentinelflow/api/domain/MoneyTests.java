/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The three money rules from ADR-0007, tested where they are enforced.
 *
 * <p>No binary floating point, never an amount without a currency, and value comparison by {@code
 * compareTo} rather than {@code equals}. Each of those is the kind of rule that is followed
 * everywhere until it is not, and the failure is silent: a rounding error nobody sees, an amount
 * that means a different thing on a different screen, a set that holds two copies of the same
 * value.
 */
class MoneyTests {

    @Test
    @DisplayName("every amount is normalised to the scale of the column")
    void amountIsNormalisedToFourDecimalPlaces() {
        // 1.5 and 1.5000 have to be the same object before they are ever
        // compared, or a value that round-trips through NUMERIC(19,4) comes
        // back unequal to the value that went in.
        assertThat(Money.of("1.5", "GBP").amount()).hasScaleOf(4);
        assertThat(Money.of("1.5", "GBP").toPlainString()).isEqualTo("1.5000");
        assertThat(Money.of("1.5", "GBP")).isEqualTo(Money.of("1.5000", "GBP"));
    }

    @Test
    @DisplayName("an amount too precise to store is rejected, not rounded")
    void excessPrecisionIsRejected() {
        // Silently discarding a digit of someone's money is not a behaviour
        // worth having a default for. The caller has to say what it intends.
        assertThatThrownBy(() -> Money.of("1.00005", "GBP"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lose precision");
    }

    @ParameterizedTest
    @ValueSource(strings = {"gbp", "GB", "GBPP", "£", "  GBP", ""})
    @DisplayName("a currency that is not an ISO 4217 alphabetic code is refused")
    void malformedCurrencyIsRefused(String currency) {
        assertThatThrownBy(() -> Money.of("1.00", currency)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("equality compares value, not scale")
    void equalityIgnoresScale() {
        // BigDecimal.equals says 1.50 and 1.5 differ. That defect passes every
        // local test and fails after the first round trip through the database.
        assertThat(new BigDecimal("1.50")).isNotEqualTo(new BigDecimal("1.5"));
        assertThat(Money.of("1.50", "GBP")).isEqualTo(Money.of("1.5", "GBP"));
    }

    @Test
    @DisplayName("hashCode agrees with equals, so a set never holds one amount twice")
    void hashCodeAgreesWithEquals() {
        Set<Money> amounts = new HashSet<>();
        amounts.add(Money.of("1.50", "GBP"));
        amounts.add(Money.of("1.5000", "GBP"));

        assertThat(amounts).hasSize(1);
    }

    @Test
    @DisplayName("the same value in a different currency is a different amount")
    void currencyIsPartOfIdentity() {
        assertThat(Money.of("1.00", "GBP")).isNotEqualTo(Money.of("1.00", "EUR"));
    }

    @Test
    @DisplayName("arithmetic across currencies throws rather than inventing a rate")
    void crossCurrencyArithmeticIsRefused() {
        Money pounds = Money.of("10.00", "GBP");
        Money euros = Money.of("10.00", "EUR");

        assertThatThrownBy(() -> pounds.plus(euros))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no exchange rate");
        assertThatThrownBy(() -> pounds.minus(euros)).isInstanceOf(IllegalArgumentException.class);
        // Two amounts in different currencies have no order either.
        assertThatThrownBy(() -> pounds.compareTo(euros)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("addition and subtraction stay exact")
    void arithmeticIsExact() {
        // The canonical binary floating-point failure: 0.1 + 0.2 is not 0.3.
        assertThat(0.1 + 0.2).isNotEqualTo(0.3);
        assertThat(Money.of("0.10", "GBP").plus(Money.of("0.20", "GBP"))).isEqualTo(Money.of("0.30", "GBP"));
        assertThat(Money.of("0.30", "GBP").minus(Money.of("0.10", "GBP"))).isEqualTo(Money.of("0.20", "GBP"));
    }

    @Test
    @DisplayName("a large amount never reaches the wire in scientific notation")
    void plainStringIsNeverScientific() {
        // BigDecimal.toString can produce 1E+3, which is a valid number and an
        // invalid value under the money pattern in contracts/schemas.
        Money thousand = Money.of(new BigDecimal("1E+3"), "GBP");

        assertThat(thousand.toPlainString()).isEqualTo("1000.0000").doesNotContain("E");
    }

    @Test
    @DisplayName("sign is preserved, because a reversal may legitimately be negative")
    void signIsPreserved() {
        Money refund = Money.of("-25.00", "GBP");

        assertThat(refund.isNegative()).isTrue();
        assertThat(refund.isZero()).isFalse();
        assertThat(Money.zero("GBP").isZero()).isTrue();
    }

    @Test
    @DisplayName("ordering within one currency follows the value")
    void orderingFollowsValue() {
        assertThat(Money.of("1.00", "GBP")).isLessThan(Money.of("2.00", "GBP"));
        assertThat(Money.of("2.00", "GBP")).isGreaterThan(Money.of("1.00", "GBP"));
        assertThat(Money.of("1.00", "GBP")).isEqualByComparingTo(Money.of("1.0000", "GBP"));
    }
}
