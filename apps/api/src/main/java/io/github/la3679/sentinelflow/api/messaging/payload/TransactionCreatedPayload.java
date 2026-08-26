/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.messaging.payload;

import java.time.Instant;
import java.util.UUID;

import io.github.la3679.sentinelflow.api.domain.IngestionSource;
import io.github.la3679.sentinelflow.api.domain.Money;
import io.github.la3679.sentinelflow.api.domain.TransactionChannel;
import io.github.la3679.sentinelflow.api.domain.TransactionType;
import io.github.la3679.sentinelflow.api.persistence.entity.Account;
import io.github.la3679.sentinelflow.api.persistence.entity.Merchant;
import io.github.la3679.sentinelflow.api.persistence.entity.TransactionRecord;

/**
 * The {@code transaction.created} payload, v1.
 *
 * <p>Field-for-field with {@code contracts/schemas/transaction-created.v1.json}, which declares
 * {@code additionalProperties: false} — so an extra field here is not an addition, it is a message
 * every conforming consumer must reject. A schema file is data as far as the compiler is concerned,
 * so {@code TransactionCreatedPayloadTests} asserts that this record's field names are exactly the
 * schema's properties, in both directions.
 *
 * <p><strong>It carries references as well as identifiers.</strong> A consumer reading a record in
 * isolation — out of a dead-letter queue, out of a console dump — cannot resolve a UUID against a
 * database it may not have. {@code accountId} is duplicated from the Kafka key for the same reason.
 *
 * <p><strong>{@code deviceReference} is required and nullable.</strong> Null rather than absent, so
 * a consumer never has to distinguish "this channel has no device" from "the producer forgot the
 * field".
 *
 * <p>This is the payload, not the envelope. Envelope fields — {@code eventId}, {@code eventType},
 * {@code correlationId}, {@code traceId} and the rest — are columns on the outbox row and are
 * assembled by the relay at publication time (ADR-0005, ADR-0006).
 */
public record TransactionCreatedPayload(
        UUID transactionId,
        String transactionReference,
        UUID accountId,
        String accountReference,
        UUID merchantId,
        String merchantReference,
        String merchantCategoryCode,
        TransactionType type,
        TransactionChannel channel,
        AmountPayload amount,
        String originCountry,
        String deviceReference,
        Instant occurredAt,
        IngestionSource ingestionSource,
        String idempotencyKey) {

    public static TransactionCreatedPayload of(TransactionRecord transaction, Account account, Merchant merchant) {
        return new TransactionCreatedPayload(
                transaction.getId(),
                transaction.getTransactionReference(),
                account.getId(),
                account.getAccountReference(),
                merchant.getId(),
                merchant.getMerchantReference(),
                merchant.getCategoryCode(),
                transaction.getType(),
                transaction.getChannel(),
                AmountPayload.of(transaction.getMoney()),
                transaction.getOriginCountry(),
                transaction.getDeviceReference(),
                transaction.getOccurredAt(),
                transaction.getIngestionSource(),
                transaction.getIdempotencyKey());
    }

    /**
     * An amount on the wire: a decimal string and its currency, never a JSON number (ADR-0007).
     *
     * <p>{@link Money#toPlainString()} rather than {@code BigDecimal.toString}, because the latter
     * can emit {@code 1E+3} — a valid number and an invalid value under the contract's {@code money}
     * pattern.
     */
    public record AmountPayload(String value, String currency) {

        public static AmountPayload of(Money money) {
            return new AmountPayload(money.toPlainString(), money.currency());
        }
    }
}
