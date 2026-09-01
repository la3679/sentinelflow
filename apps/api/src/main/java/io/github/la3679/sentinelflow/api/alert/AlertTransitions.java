/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.alert;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.la3679.sentinelflow.api.domain.ActorRole;
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

    /**
     * The moves this actor may actually make, which is what a console needs to render a control.
     *
     * <h2>Legal from the status is not the same as available to the caller</h2>
     *
     * {@code CLOSED} is legal from every live status and is an administrator's alone
     * ({@link #requiresAdministrator}), so an analyst offered it gets a {@code 403} for a button the
     * interface drew. {@code docs/development/ENGINEERING_STANDARDS.md} calls that a dead control, and the console's gate
     * forbids one. Answering "legal from here" and leaving the caller to subtract the
     * administrator's move would put a second copy of that rule in the console, which is the thing
     * this method exists to prevent.
     *
     * <p><strong>An auditor gets an empty list</strong>, which is the correct answer rather than an
     * omission: ADR-0012 §4 makes the role read-only, so there is no move it may make.
     *
     * <p>{@code SYSTEM} likewise gets nothing. It raises alerts and never works them, and it cannot
     * hold a token in any case.
     */
    public static Set<AlertStatus> legalTargetsFor(AlertStatus from, ActorRole role) {
        return switch (role) {
            case AUDITOR, SYSTEM -> Set.of();
            case ADMINISTRATOR -> legalTargetsFrom(from);
            case ANALYST ->
                legalTargetsFrom(from).stream()
                        .filter(target -> !requiresAdministrator(target))
                        .collect(Collectors.toUnmodifiableSet());
        };
    }

    /**
     * The same set as a stable, sorted list of names.
     *
     * <p>Used by both the {@code legalTargets} field on an alert and the {@code legalTargets}
     * property on the {@code 409} a refused move answers with. One producer, so a client that
     * compares what it was offered against what a refusal names cannot be shown two orderings of the
     * same answer — or, worse, two different answers.
     */
    public static List<String> namesOf(Set<AlertStatus> targets) {
        return targets.stream().map(Enum::name).sorted().toList();
    }

    /**
     * Whether this move is an administrator's to make.
     *
     * <p>Only the administrative close. ADR-0012 §4 gives an analyst the whole of working an alert -
     * picking it up, escalating it, and either disposition - and gives an administrator that plus
     * this one move. The reason it is reserved is what {@code CLOSED} means: ending an investigation
     * <em>without</em> a disposition, which is the only transition that removes work from a queue
     * while recording nothing about the transaction. That is a supervisory decision, and one an
     * analyst under queue pressure should not be able to take alone.
     *
     * <p>Stated here rather than in the service, beside the graph it qualifies. Which moves exist
     * and who may make them are two halves of one description of the workflow, and a reader who has
     * one needs the other.
     */
    public static boolean requiresAdministrator(AlertStatus target) {
        return target == AlertStatus.CLOSED;
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
