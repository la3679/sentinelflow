/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.service;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.github.la3679.sentinelflow.api.domain.EventType;
import io.github.la3679.sentinelflow.api.domain.IngestionSource;
import io.github.la3679.sentinelflow.api.messaging.payload.TransactionCreatedPayload;
import io.github.la3679.sentinelflow.api.observability.CurrentTrace;
import io.github.la3679.sentinelflow.api.persistence.entity.Account;
import io.github.la3679.sentinelflow.api.persistence.entity.Merchant;
import io.github.la3679.sentinelflow.api.persistence.entity.OutboxEvent;
import io.github.la3679.sentinelflow.api.persistence.entity.TransactionRecord;
import io.github.la3679.sentinelflow.api.persistence.repository.AccountRepository;
import io.github.la3679.sentinelflow.api.persistence.repository.MerchantRepository;
import io.github.la3679.sentinelflow.api.persistence.repository.OutboxEventRepository;
import io.github.la3679.sentinelflow.api.persistence.repository.TransactionRepository;
import io.github.la3679.sentinelflow.api.service.exception.UnknownReferenceException;
import io.github.la3679.sentinelflow.api.web.dto.TransactionRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The unit of work: one transaction row and its outbox row, written together or not at all.
 *
 * <p><strong>This is the atomicity ADR-0006 rests on.</strong> Writing the row and then publishing
 * to Kafka is two commits with a window between them; every crash in that window either loses an
 * event or publishes one describing a transaction that rolled back. Both writes below are in the
 * same database transaction, so there is no window.
 *
 * <p><strong>Separate from {@link TransactionIngestionService} on purpose.</strong> A unique-
 * constraint violation on flush dooms the persistence context — nothing more can be read or written
 * in that transaction, including the query that would find out who won the race. Recovery therefore
 * has to happen in a different transaction, and a self-invocation would bypass the proxy and quietly
 * run in the same one. Two beans make the boundary real rather than notional.
 */
@Service
public class TransactionWriter {

    /** The schema version of the payload this writes. Bumped only alongside a v2 payload schema. */
    private static final int TRANSACTION_CREATED_SCHEMA_VERSION = 1;

    private final TransactionRepository transactions;
    private final AccountRepository accounts;
    private final MerchantRepository merchants;
    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;
    private final CurrentTrace currentTrace;

    public TransactionWriter(
            TransactionRepository transactions,
            AccountRepository accounts,
            MerchantRepository merchants,
            OutboxEventRepository outbox,
            ObjectMapper objectMapper,
            CurrentTrace currentTrace) {
        this.currentTrace = currentTrace;
        this.transactions = transactions;
        this.accounts = accounts;
        this.merchants = merchants;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    /**
     * Writes the transaction and its event.
     *
     * @throws UnknownReferenceException if the account or merchant reference names nothing
     * @throws org.springframework.dao.DataIntegrityViolationException if another request committed
     *     the same idempotency key first. The caller resolves that outside this transaction.
     */
    @Transactional
    public TransactionRecord write(TransactionRequest request, UUID correlationId, IngestionSource source) {
        Account account = accounts.findByAccountReference(request.accountReference())
                .orElseThrow(() -> new UnknownReferenceException("accountReference", request.accountReference()));
        Merchant merchant = merchants
                .findByMerchantReference(request.merchantReference())
                .orElseThrow(() -> new UnknownReferenceException("merchantReference", request.merchantReference()));

        TransactionRecord transaction = new TransactionRecord(
                transactions.nextTransactionReference(),
                request.idempotencyKey(),
                account.getId(),
                merchant.getId(),
                request.type(),
                request.channel(),
                request.amount().toMoney(),
                request.originCountry(),
                request.deviceReference(),
                request.occurredAt(),
                source,
                correlationId);

        // Flushed here rather than at commit, so a duplicate key surfaces now -
        // while there is still a caller holding the request that can be told -
        // instead of during commit, where it becomes an unhandled 500.
        transactions.saveAndFlush(transaction);

        outbox.save(outboxEventFor(transaction, account, merchant, correlationId));
        return transaction;
    }

    /**
     * Finds a transaction already accepted under this account and key.
     *
     * <p>{@code REQUIRES_NEW} because the caller reaches this after a constraint violation has
     * doomed the transaction it was in. Joining that one would fail on the first statement.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<TransactionRecord> findAccepted(String accountReference, String idempotencyKey) {
        return accounts.findByAccountReference(accountReference)
                .flatMap(account -> transactions.findByAccountIdAndIdempotencyKey(account.getId(), idempotencyKey));
    }

    /**
     * Whether an existing transaction is the same submission as this request.
     *
     * <p>Everything the caller supplied except the idempotency key itself, which is what identified
     * the pair. The account is not compared either: it is part of the uniqueness, so a differing
     * account would not have found this row.
     *
     * <p>Money is compared with {@link io.github.la3679.sentinelflow.api.domain.Money}'s own
     * equality, which is by value rather than by scale — {@code 1.50} and {@code 1.5} are the same
     * amount, and treating a client's harmless formatting difference as a conflict would reject a
     * legitimate retry.
     */
    public boolean matches(TransactionRecord existing, TransactionRequest request, UUID merchantId) {
        return Objects.equals(existing.getMerchantId(), merchantId)
                && existing.getType() == request.type()
                && existing.getChannel() == request.channel()
                && Objects.equals(existing.getMoney(), request.amount().toMoney())
                && Objects.equals(existing.getOriginCountry(), request.originCountry())
                && Objects.equals(existing.getDeviceReference(), request.deviceReference())
                && sameInstant(existing.getOccurredAt(), request.occurredAt());
    }

    /**
     * PostgreSQL stores {@code timestamptz} at microsecond precision, so a nanosecond-precision
     * value does not come back identical to what went in. Comparing at microseconds compares what
     * was actually stored rather than what the client happened to send.
     */
    private static boolean sameInstant(Instant stored, Instant supplied) {
        if (stored == null || supplied == null) {
            return stored == supplied;
        }
        return stored.getEpochSecond() == supplied.getEpochSecond()
                && stored.getNano() / 1_000 == supplied.getNano() / 1_000;
    }

    @Transactional(readOnly = true)
    public Optional<Merchant> findMerchant(String merchantReference) {
        return merchants.findByMerchantReference(merchantReference);
    }

    private OutboxEvent outboxEventFor(
            TransactionRecord transaction, Account account, Merchant merchant, UUID correlationId) {
        return new OutboxEvent(
                EventType.TRANSACTION_CREATED,
                transaction.getId(),
                TRANSACTION_CREATED_SCHEMA_VERSION,
                // Keyed by account, not transaction (ADR-0006). Kafka orders
                // only within a partition, and velocity rules need one
                // account's transactions in order; keying by transaction would
                // spread an account across every partition.
                account.getAccountReference(),
                serialise(TransactionCreatedPayload.of(transaction, account, merchant)),
                correlationId,
                // The trace this row came from, so the consumer's work hangs
                // off the request that caused it rather than off the relay that
                // happened to publish it (V11). Absent outside a trace, which
                // is what the seed and any scheduled path get.
                currentTrace.stamp(),
                transaction.getOccurredAt());
    }

    private String serialise(TransactionCreatedPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            // Unreachable for a record of strings, enums and instants. If it
            // ever happens the transaction must not commit with an unpublishable
            // event sitting in its outbox, so this throws rather than storing a
            // placeholder the relay would choke on later.
            //
            // Jackson 3 moved to tools.jackson and made its exceptions
            // unchecked; the catch stays because the intent is to convert, not
            // because the compiler demands it.
            throw new IllegalStateException("Cannot serialise a transaction.created payload", e);
        }
    }
}
