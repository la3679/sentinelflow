/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.domain;

/**
 * What kind of movement a transaction represents.
 *
 * <p>Direction lives here rather than in the sign of the amount, so a rule reads an explicit type
 * instead of inferring intent from arithmetic.
 */
public enum TransactionType {
    PURCHASE,
    REFUND,
    TRANSFER,
    WITHDRAWAL,
    DEPOSIT
}
