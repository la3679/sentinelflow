/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.messaging.consumer;

import java.time.Instant;

import io.github.la3679.sentinelflow.api.domain.DlqFailureClass;
import io.github.la3679.sentinelflow.api.messaging.EventEnvelope;

/**
 * What gets written to {@code transaction.processing.dlq.v1}.
 *
 * <p>Field-for-field with {@code contracts/schemas/dlq-record.v1.json}, which sets
 * {@code additionalProperties: false}. {@code DeadLetterRecordContractIT} asserts the two have not
 * drifted, in both directions.
 *
 * <p><strong>It wraps an envelope, it is not one.</strong> {@code originalEvent} is the complete
 * original, unmodified, so reprocessing replays exactly what failed rather than a reconstruction of
 * it that happens to look similar.
 *
 * <p><strong>Nothing sensitive, and that is a design constraint rather than a habit.</strong> A
 * dead-letter topic is long-lived storage that operations staff read, which makes it the worst place
 * in the system to put a stack trace, a credential, or an unsanitised payload fragment (ADR-0006
 * §4). {@code exceptionType} is a type name and {@code sanitisedMessage} has been through
 * {@link FailureSanitiser}.
 *
 * @param consumer which consumer failed — two consumers of one topic fail independently
 * @param sourceTopic where the record came from, so it can be found again by coordinates
 * @param attemptCount how many attempts were made before this record was written; 1 for a failure
 *     classified non-retryable, because it was tried exactly once on purpose
 * @param firstFailedAt when the first of those attempts failed. Measured, not derived: see
 *     {@link RetryStateTracker} for what it does and does not survive.
 */
public record DeadLetterRecord(
        EventEnvelope originalEvent,
        String consumer,
        String sourceTopic,
        int sourcePartition,
        long sourceOffset,
        DlqFailureClass failureClass,
        String exceptionType,
        String sanitisedMessage,
        int attemptCount,
        Instant firstFailedAt,
        Instant deadLetteredAt) {}
