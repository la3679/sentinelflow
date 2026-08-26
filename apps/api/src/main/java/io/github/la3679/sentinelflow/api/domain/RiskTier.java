/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.domain;

/**
 * How much scrutiny a customer's activity attracts before any transaction is scored.
 *
 * <p>A standing property of the customer, not a conclusion about a transaction. It feeds the
 * rules; it is not itself a risk band.
 */
public enum RiskTier {
    STANDARD,
    ENHANCED,
    HIGH
}
