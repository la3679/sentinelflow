/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.seed.scenario;

import java.time.Duration;

import io.github.la3679.sentinelflow.api.web.dto.TransactionRequest;

/**
 * One generated transaction, and the shape it was planted as.
 *
 * <p><strong>It carries a {@link TransactionRequest}</strong> — the same DTO the HTTP boundary
 * validates — rather than an entity or a bag of fields. Generated data then travels the path real
 * data travels: the same Bean Validation, the same {@code TransactionWriter}, the same outbox row.
 * A generator with a private write path is a generator that can produce rows ingestion would have
 * rejected, and the first thing anyone notices is a demo that behaves differently from the product.
 *
 * @param offset how long after the run's reference instant this transaction occurred. Stored as an
 *     offset rather than an absolute time because that is the part that is deterministic: a
 *     reproduction claim is about the shape of the traffic, not about the wall clock the run
 *     happened on. {@link ScenarioManifest}'s checksum covers offsets for the same reason.
 * @param scenario the shape this transaction belongs to. Known here, deliberately never written to
 *     the database — see {@link ScenarioType}.
 */
public record GeneratedTransaction(TransactionRequest request, Duration offset, ScenarioType scenario) {}
