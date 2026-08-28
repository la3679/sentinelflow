/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.github.la3679.sentinelflow.api.alert.AlertService;
import io.github.la3679.sentinelflow.api.domain.Actor;
import io.github.la3679.sentinelflow.api.domain.ActorRole;
import io.github.la3679.sentinelflow.api.domain.AlertPriority;
import io.github.la3679.sentinelflow.api.domain.AlertStatus;
import io.github.la3679.sentinelflow.api.persistence.entity.Alert;
import io.github.la3679.sentinelflow.api.security.AuthenticatedOperator;
import io.github.la3679.sentinelflow.api.web.dto.AlertActionResponse;
import io.github.la3679.sentinelflow.api.web.dto.AlertAssignmentRequest;
import io.github.la3679.sentinelflow.api.web.dto.AlertFeedbackRequest;
import io.github.la3679.sentinelflow.api.web.dto.AlertFeedbackResponse;
import io.github.la3679.sentinelflow.api.web.dto.AlertNoteRequest;
import io.github.la3679.sentinelflow.api.web.dto.AlertResponse;
import io.github.la3679.sentinelflow.api.web.dto.AlertTransitionRequest;
import io.github.la3679.sentinelflow.api.web.dto.PageResponse;

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

    /**
     * The contract's cap, enforced rather than clamped.
     *
     * <p>No {@code @Validated} on this class, deliberately. It would put the controller behind a
     * proxy and route parameter constraints through Hibernate Validator's own
     * {@code ConstraintViolationException}, while Spring MVC's built-in method validation - which
     * needs no annotation when a parameter carries a constraint - raises
     * {@code HandlerMethodValidationException} instead. Two mechanisms would mean two exception
     * handlers answering the same question, and the one that fired would depend on an annotation
     * nobody would think to look at.
     *
     * <p>A request for more is refused with the field named. Silently returning less than was asked
     * for is how a client ends up with a quiet data-loss bug: it pages until it sees fewer rows than
     * it requested, concludes it has reached the end, and stops.
     */
    static final int MAX_PAGE_SIZE = 200;

    private final AlertService alerts;

    public AlertController(AlertService alerts) {
        this.alerts = alerts;
    }

    /**
     * One page of the queue.
     *
     * <p>Readable by every authenticated role, mutable by two of them. The filters are the ones an
     * operations screen actually asks for — what is open, what is urgent, what is on my desk — and
     * the ordering is fixed rather than client-supplied, because reordering a review queue is an
     * operational decision rather than a display one.
     */
    @GetMapping
    PageResponse<AlertResponse> queue(
            @RequestParam(required = false) AlertStatus status,
            @RequestParam(required = false) AlertPriority priority,
            @RequestParam(required = false) UUID assigneeId,
            @RequestParam(defaultValue = "0") @PositiveOrZero int page,
            @RequestParam(defaultValue = "20") @Positive @Max(MAX_PAGE_SIZE) int size,
            @AuthenticationPrincipal Jwt token) {

        // The reader's capacity, because legalTargets is a property of the
        // alert and the caller together - see AlertResponse.
        ActorRole role = AuthenticatedOperator.from(token).role();

        return PageResponse.of(
                alerts.queue(status, priority, assigneeId, PageRequest.of(page, size)),
                alert -> AlertResponse.of(alert, role));
    }

    /** One alert, for the page an analyst opens. */
    @GetMapping("/{alertId}")
    AlertResponse get(@PathVariable UUID alertId, @AuthenticationPrincipal Jwt token) {
        return AlertResponse.of(
                alerts.get(alertId), AuthenticatedOperator.from(token).role());
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

        return AlertResponse.of(moved, actor.role());
    }

    /**
     * Give the alert to somebody, or take it back.
     *
     * <p>{@code PUT} rather than {@code POST}: the request states what the assignment should be, and
     * sending it twice leaves the alert in the same place. The second call writes nothing at all —
     * the service returns early when the assignee has not changed, so a retry does not fill the
     * audit trail with rows saying an alert was assigned to whoever already held it.
     */
    @PutMapping(path = "/{alertId}/assignment", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('ANALYST', 'ADMINISTRATOR')")
    AlertResponse assign(
            @PathVariable UUID alertId,
            @Valid @RequestBody AlertAssignmentRequest request,
            @AuthenticationPrincipal Jwt token,
            HttpServletRequest httpRequest) {

        Actor actor = AuthenticatedOperator.from(token);

        Alert assigned = alerts.assign(
                alertId,
                request.assigneeId(),
                request.expectedVersion(),
                request.note(),
                actor,
                CorrelationIdFilter.currentOrNew(httpRequest));

        return AlertResponse.of(assigned, actor.role());
    }

    /**
     * Add a note to the alert's history.
     *
     * <p>201 with the created history row, because a note <em>is</em> the row: there is nothing else
     * for a caller to look at afterwards, and returning the alert would return something this
     * request did not change.
     */
    @PostMapping(path = "/{alertId}/notes", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ANALYST', 'ADMINISTRATOR')")
    AlertActionResponse addNote(
            @PathVariable UUID alertId,
            @Valid @RequestBody AlertNoteRequest request,
            @AuthenticationPrincipal Jwt token,
            HttpServletRequest httpRequest) {

        return AlertActionResponse.of(alerts.addNote(
                alertId,
                request.note(),
                AuthenticatedOperator.from(token),
                CorrelationIdFilter.currentOrNew(httpRequest)));
    }

    /**
     * Record this analyst's verdict on the decision behind the alert.
     *
     * <p>{@code PUT}, because one analyst has one verdict per assessment and sending it again
     * replaces it. A {@code POST} would suggest a second row is created, which the unique constraint
     * refuses and which would be the wrong thing to want: two opposite labels from one person about
     * one decision cannot both be training data.
     */
    @PutMapping(path = "/{alertId}/feedback", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('ANALYST', 'ADMINISTRATOR')")
    AlertFeedbackResponse recordFeedback(
            @PathVariable UUID alertId,
            @Valid @RequestBody AlertFeedbackRequest request,
            @AuthenticationPrincipal Jwt token) {

        return AlertFeedbackResponse.of(
                alerts.recordFeedback(alertId, request.label(), request.reason(), AuthenticatedOperator.from(token)));
    }

    /**
     * One page of what has been done to this alert, newest first.
     *
     * <p><strong>Readable by an auditor</strong>, and this is the endpoint that makes the role mean
     * something: read-only is a description of what somebody may do, not of what they may see. Every
     * mutation above requires an analyst or an administrator; this one requires only a token.
     *
     * <p>Paged with a server-enforced maximum, like every list endpoint here. A history that grew
     * without bound would be a denial-of-service primitive on the one table nothing ever deletes
     * from.
     */
    @GetMapping("/{alertId}/history")
    PageResponse<AlertActionResponse> history(
            @PathVariable UUID alertId,
            @RequestParam(defaultValue = "0") @PositiveOrZero int page,
            @RequestParam(defaultValue = "20") @Positive @Max(MAX_PAGE_SIZE) int size) {

        return PageResponse.of(alerts.history(alertId, PageRequest.of(page, size)), AlertActionResponse::of);
    }
}
