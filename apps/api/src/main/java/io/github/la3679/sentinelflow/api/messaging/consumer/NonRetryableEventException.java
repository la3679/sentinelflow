/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.messaging.consumer;

import io.github.la3679.sentinelflow.api.domain.DlqFailureClass;

/**
 * Thrown when an event cannot succeed on a later attempt, so retrying it is pure waste.
 *
 * <p>ADR-0006 §4 splits consumer failures in two, and the split is behavioural rather than
 * cosmetic: a retryable failure is scheduled again with backoff, and one of these goes straight to
 * the dead-letter topic. Retrying a malformed message fails identically every time while blocking
 * the partition behind it, which turns one bad record into a stalled consumer.
 *
 * <p><strong>The class is carried, not inferred.</strong> The recoverer writes {@code failureClass}
 * into the dead-letter record, and deriving it from the exception type at that point would put the
 * classification in a {@code switch} far away from the code that actually knows why.
 */
public class NonRetryableEventException extends RuntimeException {

    private final transient DlqFailureClass failureClass;

    public NonRetryableEventException(DlqFailureClass failureClass, String message) {
        super(message);
        this.failureClass = failureClass;
    }

    public NonRetryableEventException(DlqFailureClass failureClass, String message, Throwable cause) {
        super(message, cause);
        this.failureClass = failureClass;
    }

    public DlqFailureClass failureClass() {
        return failureClass;
    }
}
