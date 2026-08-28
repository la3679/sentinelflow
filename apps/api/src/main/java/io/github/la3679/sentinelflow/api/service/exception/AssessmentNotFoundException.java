/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.service.exception;

import java.util.UUID;

/**
 * This transaction has not been assessed.
 *
 * <p><strong>A normal outcome rather than a fault.</strong> Ingestion answers {@code 202} and
 * scoring happens afterwards, so between the two there is a window in which the transaction exists
 * and its assessment does not. The contract says so, and a client polling for one should read a
 * {@code 404} as "not yet" rather than as an error to report.
 *
 * <p>It also covers the case where an assessment is never coming, because the
 * {@code transaction.created} event was dead-lettered. That transaction's {@code processingStatus}
 * is {@code FAILED}, which is where a caller finds out the difference — this exception cannot tell
 * them, and pretending otherwise would mean guessing.
 */
public class AssessmentNotFoundException extends RuntimeException {

    private final UUID transactionId;

    public AssessmentNotFoundException(UUID transactionId) {
        super("No assessment exists for transaction " + transactionId);
        this.transactionId = transactionId;
    }

    public UUID transactionId() {
        return transactionId;
    }
}
