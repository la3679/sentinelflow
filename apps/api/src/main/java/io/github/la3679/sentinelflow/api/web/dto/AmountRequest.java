/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import io.github.la3679.sentinelflow.api.domain.Money;

/**
 * An amount as it arrives: a decimal <em>string</em> and an explicit currency.
 *
 * <p><strong>A string, never a JSON number</strong> (ADR-0007). {@code JSON.parse} produces a
 * {@code double}, so a JSON number is rounded by every JavaScript consumer before application code
 * sees it — the value is wrong before anyone can defend it. Binding to {@link String} and parsing
 * here means the rounding never has a chance to happen, and the pattern below is what stops a
 * client sending {@code 1e3} or {@code 1249.99999} and having it quietly accepted.
 *
 * <p>The pattern is the {@code Money} schema from {@code contracts/openapi/} verbatim. Duplicating
 * it is deliberate: a regex in an annotation is what actually rejects the request, and a contract
 * the code does not enforce is a document rather than a contract. {@code MoneyPatternTests} asserts
 * the two have not drifted.
 */
public record AmountRequest(
        @NotBlank(message = "must be present")
        @Pattern(
                regexp = AmountRequest.MONEY_PATTERN,
                message = "must be a decimal string with at most 4 fractional digits, and not in scientific"
                        + " notation")
        String value,

        @NotBlank(message = "must be present")
        @Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO 4217 alphabetic code")
        String currency) {

    /** Kept in sync with the `Money` schema in `contracts/openapi/sentinelflow-api.yaml`. */
    public static final String MONEY_PATTERN = "^-?(0|[1-9][0-9]{0,14})(\\.[0-9]{1,4})?$";

    /**
     * @throws IllegalArgumentException if this instance was never validated and holds something the
     *     pattern would have rejected
     */
    public Money toMoney() {
        return Money.of(value, currency);
    }
}
