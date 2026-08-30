/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.observability;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;

/**
 * Reads the trace the current thread is in, for storing on something that outlives it.
 *
 * <h2>Why an {@code ObjectProvider} and not a {@link Tracer}</h2>
 *
 * Tracing is configuration. It is on in the compose stack and off in the many test contexts that
 * have no collector to export to, and a component that required a {@code Tracer} bean would make
 * every one of those contexts fail to start over an observability feature they do not use. An
 * absent tracer is not an error here; it is {@link TraceStamp#absent()}, which is exactly what a
 * row written outside any trace should carry.
 *
 * <h2>The traceparent is assembled rather than read from a header</h2>
 *
 * The obvious implementation takes {@code traceparent} off the inbound request. It is also wrong in
 * the case that matters: the value on the row must point at <em>this service's</em> current span, so
 * a consumer's work hangs off the API call it came from. An inbound header names the caller's span,
 * so replaying it would attach every consumer span to the console rather than to the ingestion that
 * caused it — and only in the deployments where the console propagates one at all, which is the
 * worst kind of difference between environments.
 *
 * <p>The format is the W3C specification's four fields and the version is hard-coded to {@code 00}:
 * a later version may append fields, and emitting a version this build cannot itself parse would
 * make the value unusable to the one piece of code most likely to read it back.
 */
@Component
public class CurrentTrace {

    /** The only trace-context version this build writes or reads. */
    static final String VERSION = "00";

    /** The sampled flag. Set when the span is recorded, because an unsampled parent is not useful. */
    private static final String SAMPLED = "01";

    private static final String NOT_SAMPLED = "00";

    private final ObjectProvider<Tracer> tracer;

    public CurrentTrace(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    /**
     * The current span as something that can be stored on a row.
     *
     * <p>Never throws and never returns null. Absent when tracing is off, when there is no span, or
     * when the span is the no-op one Micrometer hands back outside a trace.
     */
    public TraceStamp stamp() {
        Tracer available = tracer.getIfAvailable();
        if (available == null) {
            return TraceStamp.absent();
        }

        Span span = available.currentSpan();
        if (span == null) {
            return TraceStamp.absent();
        }

        TraceContext context = span.context();
        String traceId = context.traceId();
        String spanId = context.spanId();
        if (traceId == null || spanId == null) {
            // The no-op span. Micrometer returns one rather than null in some
            // configurations, and its identifiers are absent rather than zeroed.
            return TraceStamp.absent();
        }

        String flags = Boolean.TRUE.equals(context.sampled()) ? SAMPLED : NOT_SAMPLED;
        return new TraceStamp(traceId, VERSION + "-" + traceId + "-" + spanId + "-" + flags);
    }
}
