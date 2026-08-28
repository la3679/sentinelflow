/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.la3679.sentinelflow.api.alert.AlertService;
import io.github.la3679.sentinelflow.api.domain.Actor;
import io.github.la3679.sentinelflow.api.persistence.entity.Alert;
import io.github.la3679.sentinelflow.api.security.AuthenticatedOperator;
import io.github.la3679.sentinelflow.api.web.dto.AlertResponse;
import io.github.la3679.sentinelflow.api.web.dto.AlertTransitionRequest;

/**
 * The alert workflow over HTTP.
 *
 * <p>Validates, delegates, and maps. Which moves are legal, who may make which one, what gets
 * audited and how a conflict is detected are all {@link AlertService}'s — a controller that decided
 * any of them would be a second place the workflow lived.
 *
 * <h2>The two authorization checks are not duplication</h2>
 *
 * {@code @PreAuthorize} here refuses an auditor before the request costs a query: ADR-0012 §4 makes
 * the auditor read-only, and that is a property of the endpoint rather than of any particular move.
 * The service applies the per-move rule — the administrative close is an administrator's — because
 * that one depends on what is being asked for. Neither subsumes the other.
 *
 * <h2>The actor comes from the token, and from nowhere else</h2>
 *
 * There is no actor field on the request body, and there will not be one. An audit trail whose actor
 * is supplied by the caller records who the caller said they were.
 */
@RestController
@RequestMapping(path = "/api/v1/alerts", produces = MediaType.APPLICATION_JSON_VALUE)
public class AlertController {

    private final AlertService alerts;

    public AlertController(AlertService alerts) {
        this.alerts = alerts;
    }

    /**
     * Move an alert to a new status.
     *
     * <p>200 rather than 204: the response carries the alert at its new version, and a client that
     * wants to act again needs that version. Making them re-read it would be a second round trip for
     * something this request already knows.
     */
    @PostMapping(path = "/{alertId}/transition", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('ANALYST', 'ADMINISTRATOR')")
    AlertResponse transition(
            @PathVariable UUID alertId,
            @Valid @RequestBody AlertTransitionRequest request,
            @AuthenticationPrincipal Jwt token,
            HttpServletRequest httpRequest) {

        Actor actor = AuthenticatedOperator.from(token);
        UUID correlationId = CorrelationIdFilter.currentOrNew(httpRequest);

        Alert moved = alerts.transition(
                alertId, request.targetStatus(), request.expectedVersion(), request.note(), actor, correlationId);

        return AlertResponse.of(moved);
    }
}
