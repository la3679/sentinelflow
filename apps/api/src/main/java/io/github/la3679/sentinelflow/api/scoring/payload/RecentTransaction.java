/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.scoring.payload;

import java.time.Instant;

import io.github.la3679.sentinelflow.api.domain.TransactionChannel;
import io.github.la3679.sentinelflow.api.domain.TransactionType;

/**
 * One earlier transaction on the same account, as the scoring service receives it.
 *
 * <p>Field-for-field with the {@code RecentTransaction} schema in
 * {@code contracts/openapi/sentinelflow-scoring.yaml}, which declares
 * {@code additionalProperties: false} — so an extra field here is not an addition, it is a request
 * the service is required to reject. {@code ScoringPayloadContractTests} asserts the two have not
 * drifted, in both directions, because a YAML file is data as far as the compiler is concerned.
 *
 * <p><strong>Deliberately thin.</strong> Enough to compute a velocity, an amount ratio or a "new
 * merchant" indicator, and nothing more. A richer shape would invite features built from whatever
 * the API happens to have lying around rather than from what the model needs, and every field here
 * is a field that leaves this service.
 *
 * <p><strong>{@code deviceReference} is required and nullable.</strong> Null rather than absent, so
 * the scoring service never has to distinguish "this channel has no device" from "the caller forgot
 * the field" — an ATM withdrawal genuinely has no device, and reading that as a new device would
 * make every cash withdrawal look novel.
 */
public record RecentTransaction(
        Instant occurredAt,
        Amount amount,
        String merchantReference,
        String deviceReference,
        String originCountry,
        TransactionChannel channel,
        TransactionType type) {}
