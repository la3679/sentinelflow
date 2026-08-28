/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.github.la3679.sentinelflow.api.domain.RiskBand;
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
     * One page of transactions, newest first, as a reader sees them.
     *
     * <p>Every filter is optional and applied only when supplied, written as {@code :param IS NULL
     * OR column = :param} for the same reason the alert queue is: the alternative is one query
     * method per combination.
     *
     * <p><strong>The band comes from the current assessment, which is the highest version.</strong>
     * A transaction may carry more than one — a rescoring under a new policy writes a new row rather
     * than editing the decision that was acted on — so the join names the version explicitly. A
     * plain join on {@code transactionId} would multiply a rescored transaction into one row per
     * assessment and page the duplicates.
     *
     * <p>The join is a {@code LEFT} one because a transaction with no assessment is a normal state
     * rather than a missing row: ingestion is asynchronous, so between the {@code 202} and the
     * consumer there is a window in which the transaction exists and nothing has scored it. An
     * inner join would hide exactly the rows an operator is most likely to be looking for.
     *
     * <p><strong>The ordering is not the caller's to choose</strong>, for the reason the alert
     * queue's is not: newest first is what a transaction feed means. {@code id} breaks ties so two
     * identical requests page identically.
     *
     * <p>An explicit count query. Spring Data cannot derive one from a constructor expression, and
     * the derived attempt would fail at startup rather than at the first call.
     *
     * <p><strong>The two instants are cast before they are tested for null, and that is not
     * decoration.</strong> PostgreSQL types every placeholder from its context, and a bare
     * {@code $n IS NULL} has none — so the statement was refused at prepare time with "could not
     * determine data type of parameter", on a query that is valid HQL and valid JPQL. The enum and
     * string filters above need no cast because Hibernate binds those with a type the driver
     * declares. Removing either cast puts every list request back to a 500 that no test of the
     * query's <em>logic</em> would catch.
     */
    @Query(value = """
            SELECT new io.github.la3679.sentinelflow.api.persistence.repository.TransactionSummaryRow(
                       t.id,
                       t.transactionReference,
                       a.accountReference,
                       m.merchantReference,
                       m.categoryCode,
                       t.type,
                       t.channel,
                       t.money.amount,
                       t.money.currency,
                       t.originCountry,
                       t.occurredAt,
                       t.ingestedAt,
                       t.processingStatus,
                       r.riskBand)
              FROM TransactionRecord t
              JOIN Account a ON t.accountId = a.id
              JOIN Merchant m ON t.merchantId = m.id
              LEFT JOIN RiskAssessment r ON r.transactionId = t.id
                   AND r.assessmentVersion = (SELECT max(r2.assessmentVersion)
                                                FROM RiskAssessment r2
                                               WHERE r2.transactionId = t.id)
             WHERE (:accountReference IS NULL OR a.accountReference = :accountReference)
               AND (:riskBand IS NULL OR r.riskBand = :riskBand)
               AND (cast(:occurredAfter as Instant) IS NULL OR t.occurredAt >= :occurredAfter)
               AND (cast(:occurredBefore as Instant) IS NULL OR t.occurredAt < :occurredBefore)
             ORDER BY t.occurredAt DESC, t.id DESC
            """, countQuery = """
            SELECT count(t)
              FROM TransactionRecord t
              JOIN Account a ON t.accountId = a.id
              LEFT JOIN RiskAssessment r ON r.transactionId = t.id
                   AND r.assessmentVersion = (SELECT max(r2.assessmentVersion)
                                                FROM RiskAssessment r2
                                               WHERE r2.transactionId = t.id)
             WHERE (:accountReference IS NULL OR a.accountReference = :accountReference)
               AND (:riskBand IS NULL OR r.riskBand = :riskBand)
               AND (cast(:occurredAfter as Instant) IS NULL OR t.occurredAt >= :occurredAfter)
               AND (cast(:occurredBefore as Instant) IS NULL OR t.occurredAt < :occurredBefore)
            """)
    Page<TransactionSummaryRow> findReadablePage(
            @Param("accountReference") String accountReference,
            @Param("riskBand") RiskBand riskBand,
            @Param("occurredAfter") Instant occurredAfter,
            @Param("occurredBefore") Instant occurredBefore,
            Pageable pageable);

    /** One transaction, in the same shape and by the same joins as a row of the page. */
    @Query("""
            SELECT new io.github.la3679.sentinelflow.api.persistence.repository.TransactionSummaryRow(
                       t.id,
                       t.transactionReference,
                       a.accountReference,
                       m.merchantReference,
                       m.categoryCode,
                       t.type,
                       t.channel,
                       t.money.amount,
                       t.money.currency,
                       t.originCountry,
                       t.occurredAt,
                       t.ingestedAt,
                       t.processingStatus,
                       r.riskBand)
              FROM TransactionRecord t
              JOIN Account a ON t.accountId = a.id
              JOIN Merchant m ON t.merchantId = m.id
              LEFT JOIN RiskAssessment r ON r.transactionId = t.id
                   AND r.assessmentVersion = (SELECT max(r2.assessmentVersion)
                                                FROM RiskAssessment r2
                                               WHERE r2.transactionId = t.id)
             WHERE t.id = :transactionId
            """)
    Optional<TransactionSummaryRow> findReadableById(@Param("transactionId") UUID transactionId);

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
