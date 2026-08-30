/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.observability;

/**
 * The trace an event came from, in the two forms the system needs it in.
 *
 * <p><strong>One value rather than two parameters</strong>, because the database refuses a row where
 * they disagree — {@code outbox_events_trace_parent_agrees_with_trace_id} — and two loose strings on
 * a constructor are two things a caller can pass in the wrong order or half of. There is no way to
 * build an inconsistent one of these.
 *
 * @param traceId the 32-hex W3C trace-id, or null when there is no trace. What the event envelope
 *     carries, so a consumer can name the request an event came from without joining anything.
 * @param traceParent the full W3C {@code traceparent}, or null. What the relay replays onto the
 *     Kafka record so the consumer <em>continues</em> that trace rather than starting a new one —
 *     which needs the parent span id, and is the reason a trace id alone is not enough.
 */
public record TraceStamp(String traceId, String traceParent) {

    private static final TraceStamp ABSENT = new TraceStamp(null, null);

    public TraceStamp {
        if ((traceId == null) != (traceParent == null)) {
            throw new IllegalArgumentException(
                    "A trace stamp carries both identifiers or neither; half of one would be written to a "
                            + "row the database refuses, or would name a trace nothing can find");
        }
    }

    /**
     * No trace, and that is an ordinary answer.
     *
     * <p>The seed, a scheduled job and anything else running outside a request has no trace to
     * continue. A fabricated identifier would be worse than an absent one: it points an operator at
     * a trace that does not exist.
     */
    public static TraceStamp absent() {
        return ABSENT;
    }

    public boolean isPresent() {
        return traceId != null;
    }
}
