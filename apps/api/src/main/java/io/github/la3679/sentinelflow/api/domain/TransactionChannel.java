/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.domain;

/**
 * How a transaction reached the system.
 *
 * <p>The single most predictive non-monetary feature in card fraud: card-present and
 * card-not-present carry very different base rates, and a channel with no device reference is
 * telling you something rather than missing data.
 */
public enum TransactionChannel {
    CARD_PRESENT,
    CARD_NOT_PRESENT,
    ONLINE_TRANSFER,
    ATM,
    DIRECT_DEBIT
}
