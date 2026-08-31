/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.alert;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.la3679.sentinelflow.api.domain.Actor;
import io.github.la3679.sentinelflow.api.domain.ActorRole;
import io.github.la3679.sentinelflow.api.domain.AlertActionType;
import io.github.la3679.sentinelflow.api.domain.AlertChangeType;
import io.github.la3679.sentinelflow.api.domain.AlertPriority;
import io.github.la3679.sentinelflow.api.domain.AlertStatus;
import io.github.la3679.sentinelflow.api.domain.EventType;
import io.github.la3679.sentinelflow.api.domain.FeedbackLabel;
import io.github.la3679.sentinelflow.api.domain.RoleCode;
import io.github.la3679.sentinelflow.api.domain.UserStatus;
import io.github.la3679.sentinelflow.api.messaging.payload.AlertUpdatedPayload;
import io.github.la3679.sentinelflow.api.observability.CurrentTrace;
import io.github.la3679.sentinelflow.api.persistence.entity.Alert;
import io.github.la3679.sentinelflow.api.persistence.entity.AlertAction;
import io.github.la3679.sentinelflow.api.persistence.entity.AnalystFeedback;
import io.github.la3679.sentinelflow.api.persistence.entity.OutboxEvent;
import io.github.la3679.sentinelflow.api.persistence.entity.User;
import io.github.la3679.sentinelflow.api.persistence.repository.AlertActionRepository;
import io.github.la3679.sentinelflow.api.persistence.repository.AlertRepository;
import io.github.la3679.sentinelflow.api.persistence.repository.AnalystFeedbackRepository;
import io.github.la3679.sentinelflow.api.persistence.repository.OutboxEventRepository;
import io.github.la3679.sentinelflow.api.persistence.repository.UserRepository;
import io.github.la3679.sentinelflow.api.service.exception.AlertClosedException;
import io.github.la3679.sentinelflow.api.service.exception.AlertNotFoundException;
import io.github.la3679.sentinelflow.api.service.exception.AlertVersionConflictException;
import io.github.la3679.sentinelflow.api.service.exception.IllegalAlertTransitionException;
import io.github.la3679.sentinelflow.api.service.exception.InsufficientRoleException;
import io.github.la3679.sentinelflow.api.service.exception.InvalidAssigneeException;
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
 * <p><strong>Who may make a move is answered in two places, deliberately.</strong> The endpoint
 * refuses an auditor outright, which is coarse and stops a request before it costs a query.
 * {@link AlertTransitions#requiresAdministrator} answers the per-move question, and this class
 * applies it — so a caller reaching the service by any route gets the same answer, and the rule sits
 * beside the graph it qualifies.
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    /** The schema version of the payload this publishes. Bumped only alongside a v2 payload schema. */
    private static final int ALERT_UPDATED_SCHEMA_VERSION = 1;

    /** Roles that can be given an alert to work. An auditor is read-only, so assigning one is not work. */
    private static final Set<RoleCode> CAN_BE_ASSIGNED = Set.of(RoleCode.ANALYST, RoleCode.ADMINISTRATOR);

    private final AlertRepository alerts;
    private final AlertActionRepository actions;
    private final OutboxEventRepository outbox;
    private final AnalystFeedbackRepository feedback;
    private final UserRepository users;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meters;
    private final CurrentTrace currentTrace;

    public AlertService(
            AlertRepository alerts,
            AlertActionRepository actions,
            OutboxEventRepository outbox,
            AnalystFeedbackRepository feedback,
            UserRepository users,
            ObjectMapper objectMapper,
            MeterRegistry meters,
            CurrentTrace currentTrace) {
        this.currentTrace = currentTrace;
        this.alerts = alerts;
        this.actions = actions;
        this.outbox = outbox;
        this.feedback = feedback;
        this.users = users;
        this.objectMapper = objectMapper;
        this.meters = meters;
        registerTransitionSeries();
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
        Alert alert = load(alertId, expectedVersion);

        AlertStatus previousStatus = alert.getStatus();
        if (!AlertTransitions.isLegal(previousStatus, target)) {
            throw new IllegalAlertTransitionException(
                    alertId, previousStatus, target, AlertTransitions.legalTargetsFrom(previousStatus));
        }

        // The authority check is here rather than only on the endpoint, and
        // both are wanted. The endpoint's role check is coarse - an auditor
        // cannot mutate an alert at all - and it is what stops a request before
        // it costs a query. This one is per-move, so a caller reaching the
        // service by any route gets the same answer, and the rule lives beside
        // the graph it qualifies rather than in an annotation a reader has to
        // go and find.
        if (AlertTransitions.requiresAdministrator(target) && actor.role() != ActorRole.ADMINISTRATOR) {
            throw new InsufficientRoleException(
                    actor.role(), ActorRole.ADMINISTRATOR, "Closing an alert without a disposition");
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
        // race surfaces, which is why flush() translates the conflict.
        flush(alert, alertId, expectedVersion);

        actions.save(AlertAction.transition(
                alertId, actor.userId(), actor.role(), previousStatus, target, note, correlationId));

        outbox.save(outboxEventFor(
                alert, AlertChangeType.STATUS_TRANSITION, previousStatus, previousAssignee, actor, correlationId, at));

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

    /**
     * Give the alert to somebody, or take it back.
     *
     * <p>One method for both, because they are one decision with two outcomes and the alert holds
     * one assignee either way. {@code alert_actions} still distinguishes {@code ASSIGNED} from
     * {@code UNASSIGNED}, because an audit reader asks which happened; the event calls both
     * {@code ASSIGNMENT} and carries both assignees, because a consumer routing on it does not.
     *
     * <p><strong>Assignment does not move the alert.</strong> Picking work up and starting it are
     * two decisions — an alert can be given to somebody who has not looked at it yet, and an analyst
     * can be reviewing one that is formally unassigned. Coupling them would make the queue lie about
     * one of the two.
     *
     * @param assigneeId who to give it to, or null to release it back to the queue
     * @throws InvalidAssigneeException if the user does not exist, cannot log in, or holds no role
     *     that can work an alert
     * @throws AlertClosedException if the investigation is already over
     */
    @Transactional
    public Alert assign(
            UUID alertId, UUID assigneeId, long expectedVersion, String note, Actor actor, UUID correlationId) {
        Alert alert = load(alertId, expectedVersion);
        if (alert.isTerminal()) {
            throw new AlertClosedException(alertId, alert.getStatus(), "Assignment");
        }
        if (assigneeId != null) {
            requireAssignable(assigneeId);
        }

        UUID previousAssignee = alert.getAssigneeId();
        if (java.util.Objects.equals(previousAssignee, assigneeId)) {
            // Nothing changed, so nothing is written. An audit row saying an
            // alert was assigned to whoever already held it is noise in the one
            // place noise is most expensive, and the event would announce a
            // change that did not happen.
            return alert;
        }

        Instant at = Instant.now();
        alert.setAssigneeId(assigneeId);
        flush(alert, alertId, expectedVersion);

        actions.save(AlertAction.of(
                alertId,
                actor.userId(),
                actor.role(),
                assigneeId == null ? AlertActionType.UNASSIGNED : AlertActionType.ASSIGNED,
                note,
                correlationId));

        outbox.save(outboxEventFor(
                alert, AlertChangeType.ASSIGNMENT, alert.getStatus(), previousAssignee, actor, correlationId, at));

        meters.counter("sentinelflow.alerts.assignments", "outcome", assigneeId == null ? "released" : "assigned")
                .increment();
        return alert;
    }

    /**
     * Record something an analyst wants the next reader to know.
     *
     * <h2>No version, because there is nothing to conflict with</h2>
     *
     * Every other operation replaces something: a status, an assignee. A note is appended, so two
     * analysts writing one at the same time both succeed and both notes are kept — which is the
     * correct outcome, and demanding {@code expectedVersion} would refuse the second for no reason a
     * user could act on. The alert row is not touched either, so its version does not move.
     *
     * <h2>No event, deliberately</h2>
     *
     * {@code alert.updated} describes a change to the alert, and a note is not one — its
     * {@code version} field would repeat the previous event's, which is exactly what a consumer uses
     * to detect a gap. And the payload has no field for the text, so the event could only announce
     * that a note exists.
     *
     * <p>That second point is the more important one. A note is an analyst's own words about a
     * transaction, which is the narrowest audience in this system: it belongs on a detail page
     * somebody has opened, and not on a topic that leaves this service. It is the same rule
     * {@code AlertRaiser} follows when it builds a summary from a reason <em>code</em> rather than a
     * reason's description.
     *
     * @throws AlertClosedException if the investigation is over. A note added after a disposition
     *     reads as though it informed one.
     */
    @Transactional
    public AlertAction addNote(UUID alertId, String note, Actor actor, UUID correlationId) {
        Alert alert = alerts.findById(alertId).orElseThrow(() -> new AlertNotFoundException(alertId));
        if (alert.isTerminal()) {
            throw new AlertClosedException(alertId, alert.getStatus(), "Adding a note");
        }
        return actions.save(
                AlertAction.of(alertId, actor.userId(), actor.role(), AlertActionType.NOTE_ADDED, note, correlationId));
    }

    /**
     * Record what an analyst concluded about the decision behind this alert.
     *
     * <h2>The label is about the assessment, not the alert</h2>
     *
     * {@code analyst_feedback.assessment_id} is what carries it, and the alert is cited beside it.
     * Rescoring writes a <em>new</em> assessment rather than editing one (ADR-0011 §2), so a label
     * attached to the alert would silently follow a decision it was never given about — and the
     * whole value of these rows is that they are labels for a model that will be trained on the
     * features of a specific scored transaction.
     *
     * <h2>One analyst, one label, revised rather than repeated</h2>
     *
     * {@code analyst_feedback_unique} is per assessment and per actor. Changing your mind updates
     * the row; it does not add a second, contradictory training label, because two opposite labels
     * from the same person about the same decision would poison a training set quietly and there is
     * no principled way to choose between them afterwards.
     *
     * <p>No {@code expectedVersion} and no conflict, therefore: nobody else can write this row. Two
     * analysts labelling the same assessment differently is not a race, it is two opinions, and both
     * are kept.
     *
     * <h2>Not audited on the alert, and not published</h2>
     *
     * A verdict is not something done <em>to</em> the alert — it does not move it, assign it or
     * change what a queue shows — so it writes no {@code alert_actions} row and no event. It is its
     * own table with its own timestamp and its own actor, which is what a training-label source has
     * to be. Whether the alert was dispositioned is already recorded, by the transition that
     * dispositioned it.
     *
     * @throws AlertClosedException if the investigation is over. A verdict is part of working the
     *     alert; recording one afterwards would let a closed case acquire a label nobody reviewed.
     */
    @Transactional
    public AnalystFeedback recordFeedback(UUID alertId, FeedbackLabel label, String reason, Actor actor) {
        Alert alert = alerts.findById(alertId).orElseThrow(() -> new AlertNotFoundException(alertId));
        if (alert.isTerminal()) {
            throw new AlertClosedException(alertId, alert.getStatus(), "Recording feedback");
        }

        return feedback.findByAssessmentIdAndActorId(alert.getAssessmentId(), actor.userId())
                .map(existing -> {
                    existing.revise(label, reason);
                    return existing;
                })
                .orElseGet(() -> feedback.save(
                        new AnalystFeedback(alert.getAssessmentId(), alertId, actor.userId(), label, reason)));
    }

    /**
     * One alert, for the page an analyst opens.
     *
     * @throws AlertNotFoundException if no alert has that identifier
     */
    @Transactional(readOnly = true)
    public Alert get(UUID alertId) {
        return alerts.findById(alertId).orElseThrow(() -> new AlertNotFoundException(alertId));
    }

    /**
     * One page of the queue.
     *
     * <p>Every filter is optional. The ordering is the queue's own and is not the caller's to
     * choose, for the reason {@link AlertRepository#findQueue} records: reordering a review queue is
     * an operational decision rather than a display one.
     */
    @Transactional(readOnly = true)
    public Page<Alert> queue(AlertStatus status, AlertPriority priority, UUID assigneeId, Pageable pageable) {
        return alerts.findQueue(status, priority, assigneeId, pageable);
    }

    /**
     * One page of what has been done to an alert.
     *
     * <p>Read-only and transactional, so the count and the page it describes come from one snapshot.
     * Without that, a row written between the two queries makes {@code totalElements} disagree with
     * what the client can actually reach.
     *
     * @throws AlertNotFoundException if no alert has that identifier. Checked rather than returning
     *     an empty page: an alert with no history does not exist — the raiser writes its first row —
     *     so an empty page would be an answer that cannot be true.
     */
    @Transactional(readOnly = true)
    public Page<AlertAction> history(UUID alertId, Pageable pageable) {
        if (!alerts.existsById(alertId)) {
            throw new AlertNotFoundException(alertId);
        }
        return actions.findByAlertIdOrderByOccurredAtDescIdDesc(alertId, pageable);
    }

    /** The alert, or the reason the caller may not act on it yet. */
    private Alert load(UUID alertId, long expectedVersion) {
        Alert alert = alerts.findById(alertId).orElseThrow(() -> new AlertNotFoundException(alertId));
        if (alert.getVersion() != expectedVersion) {
            throw new AlertVersionConflictException(alertId, expectedVersion, alert.getVersion());
        }
        return alert;
    }

    /**
     * The write, with the losing side of a race translated into the conflict the caller understands.
     *
     * <p>Null rather than a re-read for the current version: the transaction is already marked for
     * rollback, so anything this read would come from a connection that cannot commit. The caller
     * has to re-read either way.
     */
    private void flush(Alert alert, UUID alertId, long expectedVersion) {
        try {
            alerts.saveAndFlush(alert);
        } catch (OptimisticLockingFailureException racing) {
            throw new AlertVersionConflictException(alertId, expectedVersion, null);
        }
    }

    /**
     * Whether this user can be given work.
     *
     * <p>The foreign key already refuses an identifier that names nobody, but it refuses it at
     * commit with a constraint name — and the other two conditions it cannot see at all. A disabled
     * account and an auditor are both real rows, and neither can work an alert.
     */
    private void requireAssignable(UUID assigneeId) {
        User assignee =
                users.findById(assigneeId).orElseThrow(() -> new InvalidAssigneeException(assigneeId, "no such user"));
        if (assignee.getStatus() != UserStatus.ACTIVE) {
            throw new InvalidAssigneeException(assigneeId, "the account is " + assignee.getStatus());
        }
        List<RoleCode> roles = users.findRoleCodes(assigneeId);
        if (roles.stream().noneMatch(CAN_BE_ASSIGNED::contains)) {
            throw new InvalidAssigneeException(assigneeId, "no role that can work an alert; an auditor is read-only");
        }
    }

    private OutboxEvent outboxEventFor(
            Alert alert,
            AlertChangeType changeType,
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
                serialise(AlertUpdatedPayload.of(alert, changeType, previousStatus, previousAssignee, actor, at)),
                correlationId,
                // The trace this row came from, so the consumer's work hangs
                // off the request that caused it rather than off the relay that
                // happened to publish it (V11). Absent outside a trace, which
                // is what the seed and any scheduled path get.
                currentTrace.stamp(),
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
        transitions(from, to).increment();
    }

    /**
     * Every legal transition, registered at zero on startup.
     *
     * <p>Eleven series, and the number is not a guess: it is {@link AlertTransitions}' own table,
     * so a transition added there appears here without anybody remembering to add it, and an
     * illegal pair can never be registered. A dashboard of the analyst workflow is otherwise blank
     * until somebody happens to make each move, which makes "nobody has escalated anything today"
     * indistinguishable from "escalation is not instrumented".
     */
    private void registerTransitionSeries() {
        for (AlertStatus from : AlertStatus.values()) {
            for (AlertStatus to : AlertTransitions.legalTargetsFrom(from)) {
                transitions(from, to);
            }
        }
    }

    private Counter transitions(AlertStatus from, AlertStatus to) {
        return Counter.builder("sentinelflow.alerts.transitions")
                .tag("from", from.name())
                .tag("to", to.name())
                .description("Alert status transitions, by where the alert moved from and to")
                .register(meters);
    }
}
