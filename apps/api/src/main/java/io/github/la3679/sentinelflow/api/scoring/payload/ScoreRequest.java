/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.scoring.payload;

/**
 * The body of {@code POST /v1/score}.
 *
 * <p>Two halves, and the split is ADR-0008's: the transaction, and the bounded account context the
 * API computes because the scoring service has no database of its own.
 *
 * <p><strong>This is also the training record.</strong> ADR-0010 §1 requires the labelled export to
 * emit exactly this shape, produced by exactly the assembler that produces it at runtime — so a
 * model is trained on the same object it will be served. A separate training-time representation
 * would produce train/serve skew that no metric in an evaluation report can detect, because both
 * halves of the comparison would come from the training representation and would agree with each
 * other while disagreeing with production.
 */
public record ScoreRequest(TransactionToScore transaction, AccountContext accountContext) {

    /**
     * Delegates to two components that redact themselves.
     *
     * <p>Written out rather than left to the record's generated version, because the generated one
     * would be correct today only by accident: it calls {@code toString} on both components, and it
     * would silently start printing a raw field the day a third component is added.
     */
    @Override
    public String toString() {
        return "ScoreRequest[" + transaction + " " + accountContext + "]";
    }
}
