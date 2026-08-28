/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * Whether each part of the stack is answering, as this service can see it.
 *
 * <p>Field-for-field with the {@code SystemHealth} schema in {@code contracts/openapi/}.
 *
 * <p><strong>Deliberately not the actuator's shape.</strong> {@code /actuator/health} is on a
 * different base path from this contract, is serialised by Spring Boot rather than by this API, and
 * its details are closed to unauthorised callers — a smoke test asserts the closed management
 * endpoints answer 401. A screen bound to it would put a framework version bump on the console's
 * critical path (ADR-0014 §2).
 *
 * <p><strong>Consumer lag and dead-letter depth are absent, and that is the point.</strong> They are
 * Kafka's and Prometheus's, they arrive in Phase 7 with the metric set and the runbooks that make
 * them actionable, and until then the only honest thing to publish is nothing. The console says they
 * are coming rather than showing a figure nobody measured.
 */
public record SystemHealthResponse(List<Component> components, Instant checkedAt) {

    /**
     * @param state {@code OPERATIONAL} when it answered, {@code DEGRADED} when it answered in a way
     *     that says it is not fully working, {@code OUTAGE} when it did not answer at all. Never
     *     {@code UNKNOWN}: a component in this list was asked, and "we did not ask" is a state the
     *     API should not be able to report about its own dependencies.
     * @param detail one sentence a person can act on, generated here and never an exception message
     *     from a dependency — that is the log's, not the screen's.
     */
    public record Component(String componentId, String name, String state, String detail) {}
}
