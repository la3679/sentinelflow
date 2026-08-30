/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.test.simple.SimpleSpan;
import io.micrometer.tracing.test.simple.SimpleTracer;

/**
 * What gets stamped on an outbox row, and what happens when there is no trace to stamp.
 *
 * <p>The interesting half is the absence. Tracing is on in compose and off in most test contexts,
 * the seed writes rows outside any request, and the scheduler writes none at all — so "there is no
 * current span" is an ordinary path through this code rather than an edge case, and returning
 * something plausible-looking for it would put an identifier on a row that matches no trace anybody
 * can open.
 */
class TraceStampTests {

    @Test
    @DisplayName("a stamp is both identifiers or neither, because the database refuses the middle")
    void refusesHalfAStamp() {
        assertThatThrownBy(() -> new TraceStamp("4bf92f3577b34da6a3ce929d0e0e4736", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("both identifiers or neither");

        assertThatThrownBy(() -> new TraceStamp(null, "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(TraceStamp.absent().isPresent()).isFalse();
    }

    @Test
    @DisplayName("no tracer at all is an absent stamp, not a failure")
    void survivesTracingBeingSwitchedOff() {
        CurrentTrace trace = new CurrentTrace(none());

        assertThat(trace.stamp())
                .as("most test contexts have no collector and therefore no tracer; a component "
                        + "that needed one would fail every one of them at startup")
                .isEqualTo(TraceStamp.absent());
    }

    @Test
    @DisplayName("a tracer with no current span is an absent stamp")
    void survivesThereBeingNoSpan() {
        CurrentTrace trace = new CurrentTrace(only(new SimpleTracer()));

        assertThat(trace.stamp().isPresent())
                .as("the seed and the scheduler write rows outside any request")
                .isFalse();
    }

    @Test
    @DisplayName("a current span becomes a traceparent the database will accept")
    void buildsATraceparentFromTheCurrentSpan() {
        SimpleTracer tracer = new SimpleTracer();
        SimpleSpan span = tracer.nextSpan().name("ingest");
        try (Tracer.SpanInScope ignored = tracer.withSpan(span.start())) {
            TraceStamp stamp = new CurrentTrace(only(tracer)).stamp();

            assertThat(stamp.isPresent()).isTrue();

            TraceContext context = span.context();
            assertThat(stamp.traceId()).isEqualTo(context.traceId());

            // Composition, not the W3C widths. The identifiers are the tracer's
            // own, and SimpleTracer's are 16 hex characters where OpenTelemetry
            // produces the specification's 32 and 16 - so asserting the regex
            // the database enforces would be asserting a property of the fake.
            // TraceContextPropagationIT covers the real widths against the real
            // tracer, which is the only place that claim means anything.
            assertThat(stamp.traceParent())
                    .as("the database requires the two columns to agree, and this is what keeps "
                            + "the code on the right side of that")
                    .isEqualTo(CurrentTrace.VERSION + "-" + context.traceId() + "-" + context.spanId() + "-00");
        } finally {
            span.end();
        }
    }

    /** A provider for a context where tracing was never configured. */
    private static ObjectProvider<Tracer> none() {
        return new ObjectProvider<>() {
            @Override
            public Tracer getObject() {
                throw new IllegalStateException("no tracer");
            }

            @Override
            public Tracer getIfAvailable() {
                return null;
            }
        };
    }

    private static ObjectProvider<Tracer> only(Tracer tracer) {
        return new ObjectProvider<>() {
            @Override
            public Tracer getObject() {
                return tracer;
            }

            @Override
            public Tracer getIfAvailable() {
                return tracer;
            }
        };
    }
}
