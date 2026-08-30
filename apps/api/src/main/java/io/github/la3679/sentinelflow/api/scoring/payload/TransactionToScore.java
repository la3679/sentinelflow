/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.scoring.payload;

import java.time.Instant;
import java.util.UUID;

import io.github.la3679.sentinelflow.api.domain.TransactionChannel;
import io.github.la3679.sentinelflow.api.domain.TransactionType;
import io.github.la3679.sentinelflow.api.persistence.entity.Merchant;
import io.github.la3679.sentinelflow.api.persistence.entity.TransactionRecord;

/**
 * The transaction being scored.
 *
 * <p>Field-for-field with the {@code TransactionToScore} schema in
 * {@code contracts/openapi/sentinelflow-scoring.yaml}: a subset of what the API stores — enough to
 * compute features, and <strong>nothing that would only be known afterwards</strong>. That last
 * clause is why {@code processingStatus}, {@code ingestedAt} and the assessment fields are absent
 * rather than merely unused. A field that exists only after an analyst or the pipeline has acted is
 * the textbook leak, and the cheapest place to prevent it is by never putting it on the wire.
 *
 * @param transactionId carried for correlation and logging. <strong>Never used as a feature</strong>
 *     — a UUIDv7 encodes its own creation time, so a model given one would be given a clock.
 * @param merchantCategoryCode ISO 18245. A property of a merchant, not of a person.
 * @param occurredAt when the transaction happened, not when it was ingested. Every velocity feature
 *     is computed against this, and the scoring service discards context at or after it.
 */
public record TransactionToScore(
        UUID transactionId,
        String accountReference,
        String merchantReference,
        String merchantCategoryCode,
        TransactionType type,
        TransactionChannel channel,
        Amount amount,
        String originCountry,
        String deviceReference,
        Instant occurredAt) {

    public static TransactionToScore of(TransactionRecord transaction, String accountReference, Merchant merchant) {
        return new TransactionToScore(
                transaction.getId(),
                accountReference,
                merchant.getMerchantReference(),
                merchant.getCategoryCode(),
                transaction.getType(),
                transaction.getChannel(),
                Amount.of(transaction.getMoney()),
                transaction.getOriginCountry(),
                transaction.getDeviceReference(),
                transaction.getOccurredAt());
    }

    /**
     * Identifiers and classification. Never the amount, never the device handle.
     *
     * <p>ADR-0016 §4's split: an account or merchant reference is how an operator finds the thing
     * they were paged about and already appears in this API's own responses, while an amount and a
     * device handle are the parts worth withholding.
     */
    @Override
    public String toString() {
        return "TransactionToScore[" + transactionId + " account=" + accountReference + " merchant="
                + merchantReference + " type=" + type + " channel=" + channel
                + " amount and device redacted]";
    }
}
