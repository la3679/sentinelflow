/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.seed.scenario;

import java.math.BigDecimal;

/**
 * What the generator needs to know about an account, and nothing more.
 *
 * <p>A reference and a balance. Not the entity: the generator is pure and has no persistence
 * context, which is what lets {@code ScenarioGeneratorTests} assert determinism without a database
 * and lets the same generator be pointed at accounts that came from anywhere.
 *
 * @param reference the {@code ACC-000123} form, which is what a {@code TransactionRequest} names
 * @param balance the opening balance. Only {@link ScenarioType#ACCOUNT_DRAIN} reads it, and it reads
 *     it because "most of the balance" is the whole shape — a drain expressed as a fixed amount
 *     would be indistinguishable from an ordinary large withdrawal on a large account.
 */
public record GeneratorAccount(String reference, BigDecimal balance) {}
