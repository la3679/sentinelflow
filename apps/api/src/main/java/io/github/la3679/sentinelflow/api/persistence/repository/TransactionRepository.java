/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.github.la3679.sentinelflow.api.persistence.entity.TransactionRecord;

/** Transactions. */
public interface TransactionRepository extends JpaRepository<TransactionRecord, UUID> {

    /**
     * The idempotency lookup. Matches the columns of {@code transactions_idempotency_unique}, so it
     * reads the index that enforces the guarantee rather than a second one.
     */
    Optional<TransactionRecord> findByAccountIdAndIdempotencyKey(UUID accountId, String idempotencyKey);

    /**
     * Allocates the next transaction reference.
     *
     * <p>A sequence, not {@code max(reference) + 1}: the latter is a read-modify-write that two
     * concurrent ingestions both win, and the unique constraint would then reject one of them for a
     * reason that has nothing to do with the caller.
     *
     * <p>Gaps are expected. A rolled-back ingestion or a rejected duplicate consumes a value,
     * because sequences are deliberately not transactional. Nothing may infer a transaction count
     * from the highest reference issued.
     */
    @Query(value = "SELECT 'TXN-' || lpad(nextval('transaction_reference_seq')::text, 6, '0')", nativeQuery = true)
    String nextTransactionReference();

    /** Whether a reference has been issued. Used by tests and by the reference-format assertions. */
    boolean existsByTransactionReference(@Param("transactionReference") String transactionReference);

    /**
     * Records that the pipeline will not produce an assessment for this transaction.
     *
     * <p>{@code FAILED} means exactly that and not that the transaction was rejected — a rejected
     * transaction never becomes a row. It is set when a {@code transaction.created} event is
     * dead-lettered, because at that point the assessment is not late, it is not coming, and a
     * console showing the transaction as {@code PENDING} for ever would be lying about it.
     *
     * <p><strong>Guarded on the current status, which is what makes it idempotent.</strong> A
     * dead-letter record may be written more than once under at-least-once delivery, and a second
     * pass must not move a transaction that has since been assessed back to failed. The guard also
     * means the row count answers "did this change anything", which the caller logs.
     *
     * <p>A bulk update rather than a load-mutate-flush, so it does not depend on the entity being
     * managed in the calling transaction — the recoverer has no persistence context of its own.
     */
    @Modifying
    @Query("""
            UPDATE TransactionRecord t
               SET t.processingStatus = io.github.la3679.sentinelflow.api.domain.ProcessingStatus.FAILED
             WHERE t.id = :id
               AND t.processingStatus = io.github.la3679.sentinelflow.api.domain.ProcessingStatus.PENDING
            """)
    int markProcessingFailed(@Param("id") UUID id);

    /**
     * The account history behind one scoring request. One indexed read over
     * {@code transactions_account_occurred_idx}, which is {@code (account_id, occurred_at DESC)} and
     * therefore serves both the filter and the ordering without a sort.
     *
     * <p><strong>{@code occurredAt < :before}, strictly.</strong> That is what excludes the
     * transaction being scored, since {@code before} is its own {@code occurredAt} — and it does it
     * without needing the transaction's identifier, so the same query serves a transaction that has
     * been persisted and one that has not. It also drops anything at the same instant, which is
     * correct rather than incidental: the scoring service discards context at or after the scored
     * instant anyway, so sending it would spend the cap on rows guaranteed to be thrown away.
     *
     * <p><strong>{@code id} breaks ties, and that is load-bearing.</strong> Ordering by
     * {@code occurredAt} alone leaves rows sharing an instant in whatever order the plan produces,
     * and once the result is truncated to a cap, <em>which</em> rows survive would vary between
     * runs. The same transaction would then produce different contexts, different features and
     * different scores on a retry — a non-determinism that would surface as an unreproducible score
     * long before anyone suspected an {@code ORDER BY}.
     *
     * <p>The caller asks for one row more than the cap so truncation is detected from the result
     * rather than from a second {@code count} query over the same window.
     *
     * <p>An explicit join on {@code merchantId}: {@code TransactionRecord} holds it as a plain
     * {@code UUID} rather than a {@code @ManyToOne}, so there is no traversal that could turn into
     * two hundred hidden selects.
     */
    @Query("""
            SELECT new io.github.la3679.sentinelflow.api.persistence.repository.AccountHistoryRow(
                       t.occurredAt,
                       t.money.amount,
                       t.money.currency,
                       m.merchantReference,
                       t.deviceReference,
                       t.originCountry,
                       t.channel,
                       t.type)
              FROM TransactionRecord t, Merchant m
             WHERE t.merchantId = m.id
               AND t.accountId = :accountId
               AND t.occurredAt < :before
               AND t.occurredAt >= :notBefore
             ORDER BY t.occurredAt DESC, t.id DESC
            """)
    List<AccountHistoryRow> findAccountHistoryForScoring(
            @Param("accountId") UUID accountId,
            @Param("before") Instant before,
            @Param("notBefore") Instant notBefore,
            Limit limit);
}
