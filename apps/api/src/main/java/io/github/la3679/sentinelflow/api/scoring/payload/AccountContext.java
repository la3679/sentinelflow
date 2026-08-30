/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.scoring.payload;

import java.time.Instant;
import java.util.List;

/**
 * The account history the scoring service needs and cannot look up.
 *
 * <p>Field-for-field with the {@code AccountContext} schema in
 * {@code contracts/openapi/sentinelflow-scoring.yaml}. ADR-0008 fixes this as what crosses the
 * boundary, and the reason it crosses at all is that the scoring service has no database: giving it
 * one would make two services systems of record for a table {@code apps/api} owns.
 *
 * @param contextVersion the shape of this object, currently
 *     {@link io.github.la3679.sentinelflow.api.scoring.AccountContextAssembler#CONTEXT_VERSION}.
 *     Separate from the model and feature versions because what the API can cheaply compute changes
 *     for different reasons than what the model wants.
 * @param lookbackWindowSeconds how far back {@code recentTransactions} reaches. Stated rather than
 *     assumed: a feature defined over 24 hours that only received an hour of history is not a
 *     smaller number, it is a number meaning something other than its name says, and this is what
 *     lets the service warn instead of answering confidently and wrongly.
 * @param accountOpenedAt account age is a feature; a very new account behaves differently
 * @param currentBalance what {@code balanceDrainRatio} is measured against
 * @param recentTransactions earlier transactions inside the window, <strong>newest first</strong>,
 *     excluding the one being scored, capped at
 *     {@link io.github.la3679.sentinelflow.api.scoring.AccountContextAssembler#MAX_RECENT_TRANSACTIONS}
 * @param truncated true when the account had more transactions in the window than the cap allows. A
 *     count computed from a truncated list is a floor rather than a count, and the service says so
 *     rather than reporting it as exact.
 */
public record AccountContext(
        int contextVersion,
        long lookbackWindowSeconds,
        Instant accountOpenedAt,
        Amount currentBalance,
        List<RecentTransaction> recentTransactions,
        boolean truncated) {

    /**
     * The shape of the context, never its contents.
     *
     * <p>This record holds a balance and a list of an account's recent transactions — the single
     * largest concentration of forbidden fields in the application. How many there were and whether
     * the list was truncated is what a person debugging the scoring path actually asks.
     */
    @Override
    public String toString() {
        return "AccountContext[version=" + contextVersion + " window=" + lookbackWindowSeconds + "s recent="
                + (recentTransactions == null ? 0 : recentTransactions.size()) + " truncated=" + truncated
                + " balance and history redacted]";
    }
}
