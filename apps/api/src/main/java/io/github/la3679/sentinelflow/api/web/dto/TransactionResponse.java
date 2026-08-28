/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web.dto;

import java.time.Instant;
import java.util.UUID;

import io.github.la3679.sentinelflow.api.domain.ProcessingStatus;
import io.github.la3679.sentinelflow.api.domain.RiskBand;
import io.github.la3679.sentinelflow.api.persistence.repository.TransactionSummaryRow;

/**
 * A transaction as the API describes one.
 *
 * <p>Field-for-field with the {@code Transaction} schema in {@code contracts/openapi/}, which
 * declares {@code additionalProperties: false}.
 *
 * <p><strong>{@code processingStatus} says how far this system has got</strong> — {@code PENDING},
 * {@code ASSESSED}, {@code FAILED} — and never whether a payment was authorised or declined.
 * SentinelFlow scores and decides nothing, and a field that read {@code DECLINED} would describe a
 * product this is not.
 *
 * <p>Neither the idempotency key nor the ingestion source is here. The first is a caller's own
 * bookkeeping and putting it on a read endpoint would let one client discover another's; the second
 * is an operational detail of how the row arrived, which no operations screen acts on.
 */
public record TransactionResponse(
        UUID transactionId,
        String transactionReference,
        String accountReference,
        String merchantReference,
        String merchantCategoryCode,
        String type,
        String channel,
        AmountResponse amount,
        String originCountry,
        Instant occurredAt,
        Instant ingestedAt,
        ProcessingStatus processingStatus,
        RiskBand riskBand) {

    public static TransactionResponse of(TransactionSummaryRow row) {
        return new TransactionResponse(
                row.transactionId(),
                row.transactionReference(),
                row.accountReference(),
                row.merchantReference(),
                row.merchantCategoryCode(),
                row.type().name(),
                row.channel().name(),
                new AmountResponse(row.amount().toPlainString(), row.currency()),
                row.originCountry(),
                row.occurredAt(),
                row.ingestedAt(),
                row.processingStatus(),
                row.riskBand());
    }

    /**
     * An amount, as a decimal string with an explicit currency.
     *
     * <p>Never a JSON number: a JavaScript client parsing one would round it, and money is not a
     * float anywhere in this project (ADR-0007). {@code toPlainString} rather than {@code toString}
     * so a scale that would otherwise render in scientific notation cannot.
     */
    public record AmountResponse(String value, String currency) {}
}
