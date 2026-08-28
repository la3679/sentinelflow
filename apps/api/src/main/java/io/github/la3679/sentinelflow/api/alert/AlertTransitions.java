/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.alert;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import io.github.la3679.sentinelflow.api.domain.AlertStatus;

/**
 * Which moves an investigation may make, and which it may not.
 *
 * <h2>Not configuration, deliberately</h2>
 *
 * The band thresholds and the alerting threshold are numbers, and numbers are operational decisions
 * on their own schedule — so they live in {@code application.yaml} and an assessment records the
 * version that produced it. This is not a number. Which moves exist is a definition of what an
 * investigation <em>is</em>, and a definition is not something a deployment should be able to
 * change: a stack configured to allow {@code CLOSED → NEW} would reopen closed alerts and produce an
 * audit trail no other stack could reproduce.
 *
 * <h2>The graph</h2>
 *
 * <pre>
 *   NEW ⇄ IN_REVIEW → ESCALATED
 *          │  │  │        │
 *          │  │  └────────┴──→ CONFIRMED_SUSPICIOUS   (terminal)
 *          │  └───────────────→ DISMISSED_FALSE_POSITIVE (terminal)
 *          └──────────────────→ CLOSED                (terminal)
 *   ESCALATED → IN_REVIEW is legal; every other move out of a terminal state is not.
 * </pre>
 *
 * <p><strong>A disposition needs a review.</strong> {@code NEW} cannot go straight to
 * {@code CONFIRMED_SUSPICIOUS} or {@code DISMISSED_FALSE_POSITIVE}: a disposition is a claim that
 * somebody looked, and a queue that lets an alert be dismissed without being picked up is a queue
 * that will be cleared rather than worked.
 *
 * <p><strong>{@code CLOSED} is the administrative close, and it is reachable from every live
 * state.</strong> The other two terminal states are dispositions — what the analyst concluded about
 * the transaction. {@code CLOSED} says the investigation ended without one: a duplicate, an alert
 * raised by a policy that has since been corrected, a test row. Keeping them distinct is what stops
 * "we closed it" from being counted as "we cleared it", which is the difference between an
 * operations metric and a fiction.
 *
 * <p><strong>An escalation can be handed back.</strong> {@code ESCALATED → IN_REVIEW} is legal
 * because an administrator who decides an escalation was not warranted should not have to either
 * disposition it themselves or close it administratively — both of which say something untrue. The
 * hand-back is audited like every other move, so nothing about it is silent.
 *
 * <h2>Terminal means terminal</h2>
 *
 * No state with a close time has an outgoing move, and that is load-bearing rather than tidy.
 * {@code alerts_closed_at_consistent} requires a terminal alert to have a {@code closed_at} and a
 * live one not to have one, so {@link io.github.la3679.sentinelflow.api.persistence.entity.Alert#transitionTo}
 * clears it when it moves to a live state. If a move out of a terminal state were ever legal, that
 * clearing would erase the record of when the investigation ended — the one timestamp "how long did
 * this take to resolve" is computed from. Reopening an alert is therefore not a transition at all;
 * it would be a new alert citing the same assessment, and it does not exist.
 */
public final class AlertTransitions {

    private static final Map<AlertStatus, Set<AlertStatus>> LEGAL = legalMoves();

    private AlertTransitions() {}

    /**
     * Whether an alert in {@code from} may move to {@code to}.
     *
     * <p>False for {@code from == to}. A transition to the state an alert is already in is not a
     * move, {@code alert_actions_transition_complete} refuses to record one, and accepting it would
     * write an audit row saying nothing happened.
     */
    public static boolean isLegal(AlertStatus from, AlertStatus to) {
        return LEGAL.get(from).contains(to);
    }

    /** Every status an alert in this one may move to, which is empty for a terminal state. */
    public static Set<AlertStatus> legalTargetsFrom(AlertStatus from) {
        return LEGAL.get(from);
    }

    private static Map<AlertStatus, Set<AlertStatus>> legalMoves() {
        Map<AlertStatus, Set<AlertStatus>> moves = new EnumMap<>(AlertStatus.class);

        // Picked up, or closed administratively without ever being worked.
        moves.put(AlertStatus.NEW, EnumSet.of(AlertStatus.IN_REVIEW, AlertStatus.CLOSED));

        // The working state: back to the queue, up to an administrator, or to
        // either disposition.
        moves.put(
                AlertStatus.IN_REVIEW,
                EnumSet.of(
                        AlertStatus.NEW,
                        AlertStatus.ESCALATED,
                        AlertStatus.CONFIRMED_SUSPICIOUS,
                        AlertStatus.DISMISSED_FALSE_POSITIVE,
                        AlertStatus.CLOSED));

        // An administrator dispositions it, closes it, or hands it back.
        moves.put(
                AlertStatus.ESCALATED,
                EnumSet.of(
                        AlertStatus.IN_REVIEW,
                        AlertStatus.CONFIRMED_SUSPICIOUS,
                        AlertStatus.DISMISSED_FALSE_POSITIVE,
                        AlertStatus.CLOSED));

        moves.put(AlertStatus.CONFIRMED_SUSPICIOUS, EnumSet.noneOf(AlertStatus.class));
        moves.put(AlertStatus.DISMISSED_FALSE_POSITIVE, EnumSet.noneOf(AlertStatus.class));
        moves.put(AlertStatus.CLOSED, EnumSet.noneOf(AlertStatus.class));

        // Every status, or isLegal throws a NullPointerException on the one the
        // map forgot - at runtime, on a transition somebody is waiting for.
        for (AlertStatus status : AlertStatus.values()) {
            if (!moves.containsKey(status)) {
                throw new IllegalStateException("No transitions are defined for " + status
                        + ". A status was added to the enum without deciding what an alert in it may do next.");
            }
        }
        return Map.copyOf(moves);
    }
}
