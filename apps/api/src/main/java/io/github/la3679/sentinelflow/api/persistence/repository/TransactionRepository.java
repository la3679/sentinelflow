/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
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
}
