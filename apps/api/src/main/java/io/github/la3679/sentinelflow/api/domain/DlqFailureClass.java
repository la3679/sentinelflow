/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.domain;

/**
 * Why a record ended up on the dead-letter topic.
 *
 * <p>The five values of {@code failureClass} in {@code contracts/schemas/dlq-record.v1.json}, which
 * is an {@code enum} with {@code additionalProperties: false} around it — so a sixth value here is
 * not an addition, it is a record every conforming consumer must reject.
 * {@code DeadLetterRecordContractIT} asserts the two agree in both directions.
 *
 * <p>The distinction that matters operationally is the first value against the other four:
 * {@link #RETRY_EXHAUSTED} means the failure was transient and did not clear inside the retry
 * budget, so reprocessing it once the dependency is healthy is reasonable. The rest were never
 * worth retrying, and reprocessing one without changing something first will fail identically
 * (ADR-0006 §4).
 */
public enum DlqFailureClass {

    /** Classified retryable, retried, and still failing when the budget ran out. */
    RETRY_EXHAUSTED,

    /** The envelope parsed but does not satisfy the schema it claims to be. */
    SCHEMA_VALIDATION_FAILED,

    /** An {@code eventType} this consumer has no dispatch for. */
    UNKNOWN_EVENT_TYPE,

    /** Not deserialisable at all: truncated, not JSON, or the wrong shape entirely. */
    MALFORMED_PAYLOAD,

    /** A handler said this cannot succeed on a later attempt, for a reason of its own. */
    NON_RETRYABLE_ERROR
}
