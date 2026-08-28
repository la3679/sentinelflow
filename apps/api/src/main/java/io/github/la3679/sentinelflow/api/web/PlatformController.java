/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.la3679.sentinelflow.api.service.ModelMetadataService;
import io.github.la3679.sentinelflow.api.service.SystemHealthService;
import io.github.la3679.sentinelflow.api.web.dto.ModelMetadataResponse;
import io.github.la3679.sentinelflow.api.web.dto.SystemHealthResponse;

/**
 * The two read-only screens that describe the platform rather than the work in it.
 *
 * <p>One controller for both because they are one thing to a reader — "what is this system doing,
 * and with what" — and two controllers holding one method each is a package structure that describes
 * the URL tree rather than the product.
 *
 * <p><strong>Both are compositions, and both are the API's to make</strong> (ADR-0014). The console
 * talks to no other backend (ADR-0002 §3), so anything it needs from the scoring service arrives
 * through here — with the timeout and the failure behaviour that boundary already has.
 *
 * <p><strong>Neither exposes a mutation, and that is not an omission.</strong> Promoting a model or
 * changing a threshold safely needs authorization, validation, rollback and an audit trail that do
 * not exist; a half-built promotion endpoint is worse than none.
 *
 * <p>Authenticated, like every read here. Readable by any role including {@code AUDITOR}: read-only
 * describes what somebody may do, not what they may see.
 */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class PlatformController {

    private final ModelMetadataService models;
    private final SystemHealthService health;

    public PlatformController(ModelMetadataService models, SystemHealthService health) {
        this.models = models;
        this.health = health;
    }

    /**
     * What is scoring, and what this service does with the score.
     *
     * <p><strong>200 even when the scoring service cannot be reached.</strong> The policy half is
     * this service's own and is always knowable, and it is half of what the screen is for. The body
     * says the model half is unavailable and why; answering 503 would blank a screen that can still
     * tell an operator what the alerting thresholds are — which is exactly what they would be
     * looking for during a scoring outage.
     */
    @GetMapping("/models/active")
    ModelMetadataResponse activeModel(HttpServletRequest request) {
        return models.active(CorrelationIdFilter.currentOrNew(request));
    }

    /**
     * Whether each part of the stack is answering, as this service can see it.
     *
     * <p>Always 200: "the scoring service is down" is the answer, not a failure to produce one. A
     * health endpoint that returns an error status when something is unhealthy cannot distinguish a
     * sick dependency from a sick health endpoint.
     */
    @GetMapping("/system/health")
    SystemHealthResponse systemHealth(HttpServletRequest request) {
        return health.current(CorrelationIdFilter.currentOrNew(request));
    }
}
