/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.alert;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.la3679.sentinelflow.api.domain.ActorRole;
import io.github.la3679.sentinelflow.api.domain.AlertStatus;

/**
 * The investigation graph, and three properties of it that matter more than any individual edge.
 *
 * <p>The edges are asserted because they are the workflow. The properties are asserted because a
 * later change to the graph — adding a state, allowing a hand-back, permitting a reopen — can
 * satisfy every edge test above and still break the schema or strand an alert, and the failure would
 * surface as a constraint violation on somebody's transition rather than as a red test.
 */
class AlertTransitionsTests {

    private static final Set<AlertStatus> TERMINAL =
            EnumSet.of(AlertStatus.CONFIRMED_SUSPICIOUS, AlertStatus.DISMISSED_FALSE_POSITIVE, AlertStatus.CLOSED);

    // ----------------------------------------------------------------------- //
    // The edges
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("a new alert is picked up or closed administratively, and nothing else")
    void whatANewAlertMayDo() {
        assertThat(AlertTransitions.legalTargetsFrom(AlertStatus.NEW))
                .containsExactlyInAnyOrder(AlertStatus.IN_REVIEW, AlertStatus.CLOSED);
    }

    @Test
    @DisplayName("a disposition needs a review first")
    void aDispositionCannotSkipTheReview() {
        // "Confirmed" and "dismissed" are both claims that somebody looked. A
        // queue that lets an alert be dismissed without being picked up is a
        // queue that will be cleared rather than worked.
        assertThat(AlertTransitions.isLegal(AlertStatus.NEW, AlertStatus.CONFIRMED_SUSPICIOUS))
                .isFalse();
        assertThat(AlertTransitions.isLegal(AlertStatus.NEW, AlertStatus.DISMISSED_FALSE_POSITIVE))
                .isFalse();
        assertThat(AlertTransitions.isLegal(AlertStatus.NEW, AlertStatus.ESCALATED))
                .as("escalating something nobody has read is escalating a score, not a case")
                .isFalse();
    }

    @Test
    @DisplayName("an alert under review can go back to the queue, up, or to either disposition")
    void whatAnAlertUnderReviewMayDo() {
        assertThat(AlertTransitions.legalTargetsFrom(AlertStatus.IN_REVIEW))
                .containsExactlyInAnyOrder(
                        AlertStatus.NEW,
                        AlertStatus.ESCALATED,
                        AlertStatus.CONFIRMED_SUSPICIOUS,
                        AlertStatus.DISMISSED_FALSE_POSITIVE,
                        AlertStatus.CLOSED);
    }

    @Test
    @DisplayName("an escalation can be dispositioned, closed, or handed back")
    void whatAnEscalatedAlertMayDo() {
        // The hand-back is deliberate. An administrator who decides an
        // escalation was not warranted should not have to either disposition it
        // themselves or close it administratively, because both say something
        // untrue - and the hand-back is audited like every other move.
        assertThat(AlertTransitions.legalTargetsFrom(AlertStatus.ESCALATED))
                .containsExactlyInAnyOrder(
                        AlertStatus.IN_REVIEW,
                        AlertStatus.CONFIRMED_SUSPICIOUS,
                        AlertStatus.DISMISSED_FALSE_POSITIVE,
                        AlertStatus.CLOSED);
    }

    @Test
    @DisplayName("the administrative close is reachable from every live state")
    void theAdministrativeCloseIsAlwaysAvailable() {
        // A duplicate, an alert raised by a policy since corrected, a test row:
        // ending an investigation without a disposition has to be possible from
        // wherever the alert happens to be, or somebody records a disposition
        // they do not believe in order to clear their queue.
        assertThat(AlertTransitions.isLegal(AlertStatus.NEW, AlertStatus.CLOSED))
                .isTrue();
        assertThat(AlertTransitions.isLegal(AlertStatus.IN_REVIEW, AlertStatus.CLOSED))
                .isTrue();
        assertThat(AlertTransitions.isLegal(AlertStatus.ESCALATED, AlertStatus.CLOSED))
                .isTrue();
    }

    // ----------------------------------------------------------------------- //
    // The properties
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("no state may move to itself")
    void noSelfTransitions() {
        // alert_actions_transition_complete refuses a TRANSITIONED row whose
        // previous and new status are equal, so a legal self-move would be a
        // constraint violation on a request the service had already accepted.
        for (AlertStatus status : AlertStatus.values()) {
            assertThat(AlertTransitions.isLegal(status, status))
                    .as("%s to itself", status)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("no terminal state has an outgoing move")
    void terminalMeansTerminal() {
        // Alert.transitionTo clears closed_at when it moves to a live state,
        // because alerts_closed_at_consistent requires a live alert not to have
        // one. A legal move out of a terminal state would therefore erase when
        // the investigation ended - the timestamp every resolution-time figure
        // is computed from. Reopening is not a transition; it would be a new
        // alert citing the same assessment, and it does not exist.
        for (AlertStatus terminal : TERMINAL) {
            assertThat(AlertTransitions.legalTargetsFrom(terminal))
                    .as("%s is terminal", terminal)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("every live state can reach a terminal one")
    void noAlertCanBeStranded() {
        // A live state with no path to a terminal one is an alert that can never
        // be finished: it stays on the queue for ever, and no report about open
        // work is ever true again. Breadth-first rather than by inspection,
        // because the point is to catch a state somebody adds later.
        for (AlertStatus status : AlertStatus.values()) {
            if (TERMINAL.contains(status)) {
                continue;
            }
            assertThat(canReachTerminal(status))
                    .as("%s has no path to a terminal state", status)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("every status has an answer, including one added later")
    void everyStatusIsCovered() {
        // legalTargetsFrom on an unmapped status would be a NullPointerException
        // at runtime, on a transition somebody is waiting for. The class refuses
        // to initialise instead; this asserts the map it built is complete.
        for (AlertStatus status : AlertStatus.values()) {
            assertThat(AlertTransitions.legalTargetsFrom(status))
                    .as("%s has no entry", status)
                    .isNotNull();
        }
    }

    // ----------------------------------------------------------------------- //
    // What a caller may actually do, which is not the same question
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("an analyst is never offered the administrative close")
    void ananalystNeverSeesTheAdministrativeClose() {
        for (AlertStatus status : AlertStatus.values()) {
            assertThat(AlertTransitions.legalTargetsFor(status, ActorRole.ANALYST))
                    .as("%s", status)
                    .doesNotContain(AlertStatus.CLOSED);
        }
    }

    @Test
    @DisplayName("an analyst is offered every other legal move, so nothing legal is hidden")
    void ananalystSeesEverythingElse() {
        for (AlertStatus status : AlertStatus.values()) {
            Set<AlertStatus> expected = AlertTransitions.legalTargetsFrom(status).stream()
                    .filter(target -> target != AlertStatus.CLOSED)
                    .collect(Collectors.toUnmodifiableSet());

            // The failure this guards against is the opposite of a dead
            // control and just as bad: a move an analyst is entitled to make
            // and no button for it.
            assertThat(AlertTransitions.legalTargetsFor(status, ActorRole.ANALYST))
                    .as("%s", status)
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("an administrator is offered exactly what the state machine allows")
    void anAdministratorSeesTheWholeMap() {
        for (AlertStatus status : AlertStatus.values()) {
            assertThat(AlertTransitions.legalTargetsFor(status, ActorRole.ADMINISTRATOR))
                    .as("%s", status)
                    .isEqualTo(AlertTransitions.legalTargetsFrom(status));
        }
    }

    @Test
    @DisplayName("an auditor is offered nothing, from any status")
    void anAuditorIsOfferedNothing() {
        // ADR-0012 section 4. Read-only is not "read-only except the moves the
        // state machine happens to allow", and an empty list is the correct
        // answer rather than a missing one.
        for (AlertStatus status : AlertStatus.values()) {
            assertThat(AlertTransitions.legalTargetsFor(status, ActorRole.AUDITOR))
                    .as("%s", status)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("the system principal is offered nothing either")
    void theSystemPrincipalIsOfferedNothing() {
        // It raises alerts and never works them, and it cannot hold a token in
        // any case - but a switch that fell through for it would be a runtime
        // failure on a path nobody exercises.
        for (AlertStatus status : AlertStatus.values()) {
            assertThat(AlertTransitions.legalTargetsFor(status, ActorRole.SYSTEM))
                    .as("%s", status)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("the names are sorted, so two readings of one answer cannot differ")
    void namesAreSorted() {
        // The alert's legalTargets field and the 409's legalTargets property
        // both come from here. A client comparing them must not be shown two
        // orderings of the same set.
        assertThat(AlertTransitions.namesOf(AlertTransitions.legalTargetsFrom(AlertStatus.IN_REVIEW)))
                .containsExactly("CLOSED", "CONFIRMED_SUSPICIOUS", "DISMISSED_FALSE_POSITIVE", "ESCALATED", "NEW");
        assertThat(AlertTransitions.namesOf(Set.of())).isEmpty();
    }

    private static boolean canReachTerminal(AlertStatus from) {
        Set<AlertStatus> seen = EnumSet.of(from);
        Deque<AlertStatus> queue = new ArrayDeque<>(Set.of(from));
        while (!queue.isEmpty()) {
            AlertStatus current = queue.removeFirst();
            if (TERMINAL.contains(current)) {
                return true;
            }
            for (AlertStatus next : AlertTransitions.legalTargetsFrom(current)) {
                if (seen.add(next)) {
                    queue.addLast(next);
                }
            }
        }
        return false;
    }
}
