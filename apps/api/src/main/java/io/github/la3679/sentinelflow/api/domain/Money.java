/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.domain;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * An exact monetary amount with its currency.
 *
 * <p>The only shape in which money exists inside this application. ADR-0007 requires that no binary
 * floating point ever touches a monetary value, that an amount is never stored or transmitted
 * without an explicit ISO 4217 code, and that value comparison uses {@link BigDecimal#compareTo}
 * rather than {@link BigDecimal#equals}. Putting all three in one type is what stops them from
 * being three conventions someone has to remember at every call site.
 *
 * <p><strong>Scale.</strong> Every instance is normalised to exactly four fractional digits on
 * construction, matching the {@code NUMERIC(19,4)} column. Doing it here rather than at the
 * database boundary means {@code 1.5} and {@code 1.5000} are the same object before they are ever
 * compared, so a value that round-trips through PostgreSQL comes back equal to the value that went
 * in. An amount with more than four fractional digits is rejected rather than rounded: silently
 * discarding a digit of someone's money is not a behaviour worth having a default for.
 *
 * <p><strong>Equality.</strong> Two amounts are equal when their currencies match and their values
 * compare equal. {@code BigDecimal.equals} compares scale as well as value, which makes {@code
 * 1.50} unequal to {@code 1.5} - a defect that passes every local test and fails after the first
 * round trip through the database.
 *
 * <p><strong>Arithmetic.</strong> Adding or subtracting across currencies throws. There is no
 * exchange rate in this system, and an implicit one would be an invented number.
 */
@Embeddable
public final class Money implements Serializable, Comparable<Money> {

    /** Matches {@code NUMERIC(19, 4)} and the {@code money} type in {@code contracts/schemas}. */
    public static final int SCALE = 4;

    @Column(name = "amount", nullable = false, precision = 19, scale = SCALE)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** For Hibernate only. */
    protected Money() {
        // Hibernate instantiates embeddables reflectively and populates the
        // fields directly; it never calls of().
    }

    private Money(BigDecimal amount, String currency) {
        this.amount = amount;
        this.currency = currency;
    }

    /**
     * @param amount the value; must have no more than {@value #SCALE} fractional digits
     * @param currency an ISO 4217 alphabetic code, upper case
     * @throws IllegalArgumentException if the currency is malformed or the amount is too precise to
     *     store without losing a digit
     */
    public static Money of(BigDecimal amount, String currency) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");

        if (!currency.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("Currency must be an ISO 4217 alphabetic code: " + currency);
        }
        if (amount.scale() > SCALE) {
            // UNNECESSARY throws rather than rounds when a digit would be lost,
            // which is precisely the behaviour wanted: the caller has to say
            // what rounding it intends.
            throw new IllegalArgumentException(
                    "Amount has more than " + SCALE + " fractional digits and would lose precision: " + amount);
        }
        return new Money(amount.setScale(SCALE, RoundingMode.UNNECESSARY), currency);
    }

    /** Parses the decimal-string representation used by the API and event contracts. */
    public static Money of(String amount, String currency) {
        return of(new BigDecimal(amount), currency);
    }

    /** Zero in the given currency. */
    public static Money zero(String currency) {
        return of(BigDecimal.ZERO, currency);
    }

    public BigDecimal amount() {
        return amount;
    }

    public String currency() {
        return currency;
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    /**
     * The plain decimal string this amount is transmitted as.
     *
     * <p>Never scientific notation: {@code BigDecimal.toString} can produce {@code 1E+3}, which is
     * a valid number and an invalid value under the {@code money} pattern in the contracts.
     */
    public String toPlainString() {
        return amount.toPlainString();
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Cannot combine " + currency + " and " + other.currency + ": there is no exchange rate here");
        }
    }

    /**
     * @throws IllegalArgumentException if the currencies differ; two amounts in different
     *     currencies have no order
     */
    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Money that)) {
            return false;
        }
        return currency.equals(that.currency) && amount.compareTo(that.amount) == 0;
    }

    @Override
    public int hashCode() {
        // stripTrailingZeros so that equal values hash alike regardless of the
        // scale they arrived with. Without it equals and hashCode disagree, and
        // a HashSet quietly holds two copies of the same amount.
        return Objects.hash(currency, amount.stripTrailingZeros());
    }

    @Override
    public String toString() {
        return toPlainString() + " " + currency;
    }
}
