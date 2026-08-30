/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.scoring.payload;

import io.github.la3679.sentinelflow.api.domain.Money;

/**
 * An amount on the scoring wire: a decimal string and its currency, never a JSON number (ADR-0007).
 *
 * <p><strong>Deliberately not shared with {@code TransactionCreatedPayload.AmountPayload}</strong>,
 * which today has identical fields. They belong to two different contracts —
 * {@code contracts/schemas/transaction-created.v1.json} and
 * {@code contracts/openapi/sentinelflow-scoring.yaml} — that version independently and are consumed
 * by different services. Sharing one record would mean a change made for the event schema silently
 * altering an HTTP request body, which is the coupling ADR-0006's compatibility policy exists to
 * avoid. The same reasoning is why {@code AmountRequest} duplicates the money pattern rather than
 * importing it.
 *
 * <p>{@link Money#toPlainString()} rather than {@code BigDecimal.toString}, because the latter can
 * emit {@code 1E+3} — a valid number and an invalid value under the contract's {@code money}
 * pattern.
 */
public record Amount(String value, String currency) {

    public static Amount of(Money money) {
        return new Amount(money.toPlainString(), money.currency());
    }

    /** The currency alone. An amount is forbidden in a log at every level (ADR-0016 §4). */
    @Override
    public String toString() {
        return "Amount[" + currency + " redacted]";
    }
}
