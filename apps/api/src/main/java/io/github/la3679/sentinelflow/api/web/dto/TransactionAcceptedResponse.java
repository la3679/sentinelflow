/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web.dto;

import java.util.UUID;

/**
 * What ingestion returns: the transaction exists, and nothing has been decided about it yet.
 *
 * <p>{@code status} is always {@code ACCEPTED} and is a fixed enumeration of one in the contract.
 * That is not filler — it is the field that will grow if ingestion ever gains a second outcome, and
 * a client that switches on it today does not have to change shape then.
 *
 * <p>No risk band, no score, no assessment. Ingestion is asynchronous; the assessment does not
 * exist when this is written, and inventing a placeholder for it would be a claim about a
 * transaction nobody has scored.
 */
public record TransactionAcceptedResponse(
        UUID transactionId, String transactionReference, UUID correlationId, String status) {

    private static final String ACCEPTED = "ACCEPTED";

    public static TransactionAcceptedResponse of(UUID transactionId, String transactionReference, UUID correlationId) {
        return new TransactionAcceptedResponse(transactionId, transactionReference, correlationId, ACCEPTED);
    }
}
