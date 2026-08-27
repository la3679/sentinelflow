/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.repository;

import java.math.BigDecimal;
import java.time.Instant;

import io.github.la3679.sentinelflow.api.domain.TransactionChannel;
import io.github.la3679.sentinelflow.api.domain.TransactionType;

/**
 * One row of account history, projected for the scoring context.
 *
 * <p>A projection rather than a {@code TransactionRecord}: the context needs seven columns of a
 * fifteen-column table joined to one column of another, and loading two hundred managed entities to
 * read seven fields each would put them all in the persistence context, where every one of them
 * becomes a dirty-check candidate at flush.
 *
 * <p><strong>The amount arrives as its two columns rather than as a {@code Money}.</strong>
 * {@code Money} is an {@code @Embeddable} and Hibernate can select one, but a constructor expression
 * that nests a second {@code new} is not portable JPQL — and the assembler has to construct a
 * {@code Money} anyway to reach {@code toPlainString()}, so nothing is saved by pushing it down.
 *
 * @param merchantReference from the join. {@code TransactionRecord} holds {@code merchantId} as a
 *     plain {@code UUID} rather than a {@code @ManyToOne} (Phase 2), so this is an explicit join in
 *     the query rather than a traversal that would have been a hidden per-row select.
 */
public record AccountHistoryRow(
        Instant occurredAt,
        BigDecimal amount,
        String currency,
        String merchantReference,
        String deviceReference,
        String originCountry,
        TransactionChannel channel,
        TransactionType type) {}
