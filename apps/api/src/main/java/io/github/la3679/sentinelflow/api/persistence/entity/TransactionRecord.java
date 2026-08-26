/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import io.github.la3679.sentinelflow.api.domain.IngestionSource;
import io.github.la3679.sentinelflow.api.domain.Money;
import io.github.la3679.sentinelflow.api.domain.ProcessingStatus;
import io.github.la3679.sentinelflow.api.domain.TransactionChannel;
import io.github.la3679.sentinelflow.api.domain.TransactionType;

/**
 * A synthetic transaction: the thing this system exists to assess.
 *
 * <p><strong>The class is {@code TransactionRecord} and the table is {@code transactions}.</strong>
 * {@code Transaction} is taken by {@code jakarta.transaction.Transaction} and by Hibernate's own
 * {@code Transaction}, and a domain type that collides with the transaction API in every file that
 * touches both is a name that costs a fully-qualified import forever. The table name is the domain
 * name; only the Java identifier is disambiguated.
 *
 * <p><strong>{@code occurredAt} and {@code ingestedAt} are separate facts.</strong> A replayed
 * scenario occurred whenever the scenario says it did and was ingested now. Collapsing them makes
 * every replayed transaction look as though it happened at import time, which destroys every
 * velocity feature computed from it.
 *
 * <p><strong>{@code idempotencyKey} is unique per account, not globally.</strong> Ingestion is
 * at-least-once by design (ADR-0006), so a retried submission is normal traffic; the database
 * constraint - not a check-then-insert in application code, which has a window between its two
 * statements - is what makes the retry return the original result even under a race.
 */
@Entity
@Table(name = "transactions")
public class TransactionRecord extends AbstractEntity {

    @Column(name = "transaction_reference", nullable = false, length = 16, updatable = false)
    private String transactionReference;

    @Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
    private String idempotencyKey;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16, updatable = false)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 24, updatable = false)
    private TransactionChannel channel;

    /** Column names already match {@link Money}'s own, so no override is needed here. */
    @Embedded
    private Money money;

    @Column(name = "origin_country", nullable = false, length = 2, updatable = false)
    private String originCountry;

    @Column(name = "device_reference", length = 16, updatable = false)
    private String deviceReference;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @CreationTimestamp
    @Column(name = "ingested_at", nullable = false, updatable = false)
    private Instant ingestedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "ingestion_source", nullable = false, length = 16, updatable = false)
    private IngestionSource ingestionSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 16)
    private ProcessingStatus processingStatus;

    @Column(name = "correlation_id", nullable = false, updatable = false)
    private UUID correlationId;

    /**
     * Optimistic lock. Ingestion writes the row and scoring updates its status from another thread;
     * a lost update there would leave a transaction permanently PENDING with no error anywhere.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TransactionRecord() {}

    @SuppressWarnings("checkstyle:ParameterNumber")
    public TransactionRecord(
            String transactionReference,
            String idempotencyKey,
            UUID accountId,
            UUID merchantId,
            TransactionType type,
            TransactionChannel channel,
            Money money,
            String originCountry,
            String deviceReference,
            Instant occurredAt,
            IngestionSource ingestionSource,
            UUID correlationId) {
        this.transactionReference = transactionReference;
        this.idempotencyKey = idempotencyKey;
        this.accountId = accountId;
        this.merchantId = merchantId;
        this.type = type;
        this.channel = channel;
        this.money = money;
        this.originCountry = originCountry;
        this.deviceReference = deviceReference;
        this.occurredAt = occurredAt;
        this.ingestionSource = ingestionSource;
        this.correlationId = correlationId;
        // A transaction is unassessed the instant it exists. Letting a caller
        // choose would let ingestion claim a score it has not asked for yet.
        this.processingStatus = ProcessingStatus.PENDING;
    }

    public String getTransactionReference() {
        return transactionReference;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public TransactionType getType() {
        return type;
    }

    public TransactionChannel getChannel() {
        return channel;
    }

    public Money getMoney() {
        return money;
    }

    public String getOriginCountry() {
        return originCountry;
    }

    public String getDeviceReference() {
        return deviceReference;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getIngestedAt() {
        return ingestedAt;
    }

    public IngestionSource getIngestionSource() {
        return ingestionSource;
    }

    public ProcessingStatus getProcessingStatus() {
        return processingStatus;
    }

    public void setProcessingStatus(ProcessingStatus processingStatus) {
        this.processingStatus = processingStatus;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
