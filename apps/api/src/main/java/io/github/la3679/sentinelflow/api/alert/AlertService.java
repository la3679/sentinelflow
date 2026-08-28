/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.alert;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.la3679.sentinelflow.api.domain.Actor;
import io.github.la3679.sentinelflow.api.domain.AlertChangeType;
import io.github.la3679.sentinelflow.api.domain.AlertStatus;
import io.github.la3679.sentinelflow.api.domain.EventType;
import io.github.la3679.sentinelflow.api.messaging.payload.AlertUpdatedPayload;
import io.github.la3679.sentinelflow.api.persistence.entity.Alert;
import io.github.la3679.sentinelflow.api.persistence.entity.AlertAction;
import io.github.la3679.sentinelflow.api.persistence.entity.OutboxEvent;
import io.github.la3679.sentinelflow.api.persistence.repository.AlertActionRepository;
import io.github.la3679.sentinelflow.api.persistence.repository.AlertRepository;
import io.github.la3679.sentinelflow.api.persistence.repository.OutboxEventRepository;
import io.github.la3679.sentinelflow.api.service.exception.AlertNotFoundException;
import io.github.la3679.sentinelflow.api.service.exception.AlertVersionConflictException;
import io.github.la3679.sentinelflow.api.service.exception.IllegalAlertTransitionException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * What a person may do to an alert once it exists.
 *
 * <h2>Three writes, one commit</h2>
 *
 * The alert, its history row and its {@code alert.updated} outbox row are written together. An
 * alert that had moved with no history entry saying who moved it would be exactly the
 * unattributable change to a reviewed decision that {@code alert_actions.actor_id} is
 * {@code NOT NULL} to prevent, and an announcement of a transition that then rolled back would tell
 * every consumer something that never happened.
 *
 * <h2>The concurrency check is made twice, deliberately</h2>
 *
 * {@link #transition} compares the caller's {@code expectedVersion} against the loaded alert, and
 * the persistence provider compares it again at flush. Neither is redundant:
 *
 * <ul>
 *   <li>The <strong>explicit check</strong> is for a caller working from a stale read — the analyst
 *       who opened the alert five minutes ago. It fails before anything is written, and it can say
 *       which version the alert is actually at.
 *   <li>The <strong>provider's check</strong> is for two requests that both passed the explicit one
 *       and are racing. The explicit check is a read followed by a write, so nothing about it is
 *       atomic; {@code @Version} on the UPDATE is what makes the second writer lose.
 * </ul>
 *
 * Both surface as the same conflict, because from the caller's side they are the same thing: the
 * alert moved on, re-read it and decide again.
 *
 * <h2>What this does not decide</h2>
 *
 * <p><strong>Which moves are legal is {@link AlertTransitions}'.</strong> A condition written here
 * would be a second answer to that question, and the graph is a definition rather than a rule this
 * class gets to have an opinion about.
 *
 * <p><strong>Who may make a move is not answered here yet.</strong> The actor is a parameter, and
 * the role on it is recorded rather than consulted. Role authorization arrives with authentication,
 * at the boundary where the actor comes from a token rather than from a caller who could assert
 * anything.
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    /** The schema version of the payload this publishes. Bumped only alongside a v2 payload schema. */
    private static final int ALERT_UPDATED_SCHEMA_VERSION = 1;

    private final AlertRepository alerts;
    private final AlertActionRepository actions;
    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meters;

    public AlertService(
            AlertRepository alerts,
            AlertActionRepository actions,
            OutboxEventRepository outbox,
            ObjectMapper objectMapper,
            MeterRegistry meters) {
        this.alerts = alerts;
        this.actions = actions;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.meters = meters;
    }

    /**
     * Move an alert to a new status, recording who moved it and announcing that it moved.
     *
     * @param alertId the alert to move
     * @param target where to move it. Rejected unless {@link AlertTransitions} permits the move from
     *     where the alert currently is.
     * @param expectedVersion the version the caller believes the alert is at
     * @param note the actor's own words, or null. Stored as text and never interpreted.
     * @param actor who is moving it, and in what capacity
     * @param correlationId ties this to the request's logs, its audit row and its event
     * @return the alert as it now stands, at its new version
     * @throws AlertNotFoundException if no alert has that identifier
     * @throws AlertVersionConflictException if the alert has changed since the caller read it
     * @throws IllegalAlertTransitionException if the state machine does not permit the move
     */
    @Transactional
    public Alert transition(
            UUID alertId, AlertStatus target, long expectedVersion, String note, Actor actor, UUID correlationId) {
        Alert alert = alerts.findById(alertId).orElseThrow(() -> new AlertNotFoundException(alertId));

        if (alert.getVersion() != expectedVersion) {
            throw new AlertVersionConflictException(alertId, expectedVersion, alert.getVersion());
        }

        AlertStatus previousStatus = alert.getStatus();
        if (!AlertTransitions.isLegal(previousStatus, target)) {
            throw new IllegalAlertTransitionException(
                    alertId, previousStatus, target, AlertTransitions.legalTargetsFrom(previousStatus));
        }

        // One instant for all three rows. The history says when the analyst
        // acted and the event says when the change happened; two calls to the
        // clock would let them disagree by however long the flush took.
        Instant at = Instant.now();
        UUID previousAssignee = alert.getAssigneeId();

        alert.transitionTo(target, at);

        // Flushed rather than left to the end of the transaction, because the
        // payload below needs the version the provider assigns and that value
        // does not exist until the UPDATE is written. It is also where a losing
        // race surfaces, which is why the conflict is translated here.
        try {
            alerts.saveAndFlush(alert);
        } catch (OptimisticLockingFailureException racing) {
            // Null rather than a re-read: the transaction is already marked for
            // rollback, and the version this reported would be read in a
            // connection that cannot commit. The caller has to re-read anyway.
            throw new AlertVersionConflictException(alertId, expectedVersion, null);
        }

        actions.save(AlertAction.transition(
                alertId, actor.userId(), actor.role(), previousStatus, target, note, correlationId));

        outbox.save(outboxEventFor(alert, previousStatus, previousAssignee, actor, correlationId, at));

        count(previousStatus, target);
        log.debug(
                "Alert {} moved {} to {} by {} ({})",
                alert.getAlertReference(),
                previousStatus,
                target,
                actor.userId(),
                actor.role());
        return alert;
    }

    private OutboxEvent outboxEventFor(
            Alert alert,
            AlertStatus previousStatus,
            UUID previousAssignee,
            Actor actor,
            UUID correlationId,
            Instant at) {
        return new OutboxEvent(
                EventType.ALERT_UPDATED,
                alert.getId(),
                ALERT_UPDATED_SCHEMA_VERSION,
                // Keyed by the alert, as alert.created is: one alert's changes
                // are ordered against each other and have no ordering
                // relationship with any other alert (ADR-0006 section 3).
                alert.getId().toString(),
                serialise(AlertUpdatedPayload.of(
                        alert, AlertChangeType.STATUS_TRANSITION, previousStatus, previousAssignee, actor, at)),
                correlationId,
                // Trace context arrives with OpenTelemetry in Phase 7.
                null,
                at);
    }

    private String serialise(AlertUpdatedPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialise an alert.updated payload", e);
        }
    }

    /**
     * One counter per transition, tagged by where the alert came from and where it went.
     *
     * <p>Six statuses give at most a few dozen pairs, which is bounded and stays bounded. It is the
     * counter every operational question about the queue is asked of: how much work is being
     * dispositioned rather than closed, how often an escalation is handed back, and whether
     * confirmations move with alert volume — which is the evidence ADR-0011 §4 says the alerting
     * threshold should eventually be revisited against.
     */
    private void count(AlertStatus from, AlertStatus to) {
        Counter.builder("sentinelflow.alerts.transitions")
                .tag("from", from.name())
                .tag("to", to.name())
                .description("Alert status transitions, by where the alert moved from and to")
                .register(meters)
                .increment();
    }
}
