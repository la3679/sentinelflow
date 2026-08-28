/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import io.github.la3679.sentinelflow.api.domain.ProcessingStatus;
import io.github.la3679.sentinelflow.api.domain.RiskBand;
import io.github.la3679.sentinelflow.api.domain.TransactionChannel;
import io.github.la3679.sentinelflow.api.domain.TransactionType;

/**
 * One transaction as a reader sees it, assembled in the database.
 *
 * <p>A projection rather than the entity, because the entity holds an account and a merchant as
 * bare {@code UUID}s and the contract's {@code Transaction} publishes their <em>references</em> —
 * {@code ACC-000123}, {@code MER-0042}. Per ADR-0007 the reference is the handle a person uses and
 * the identifier is the key, so a response carrying only the key would be one no operator could
 * read. Resolving them per row afterwards would be the N+1 this join exists to avoid.
 *
 * <p><strong>{@code riskBand} is nullable and that is a real state.</strong> Ingestion is
 * asynchronous, so a transaction legitimately exists for a while with no assessment behind it. Null
 * here means "not scored yet", which is what {@code processingStatus} says in words.
 */
public record TransactionSummaryRow(
        UUID transactionId,
        String transactionReference,
        String accountReference,
        String merchantReference,
        String merchantCategoryCode,
        TransactionType type,
        TransactionChannel channel,
        BigDecimal amount,
        String currency,
        String originCountry,
        Instant occurredAt,
        Instant ingestedAt,
        ProcessingStatus processingStatus,
        RiskBand riskBand) {}
