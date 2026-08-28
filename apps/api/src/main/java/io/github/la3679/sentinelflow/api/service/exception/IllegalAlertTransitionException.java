/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.service.exception;

import java.util.Set;
import java.util.UUID;

import io.github.la3679.sentinelflow.api.domain.AlertStatus;

/**
 * The state machine does not permit this move.
 *
 * <p><strong>A conflict, not a validation failure.</strong> The request is well formed and the
 * target is a real status; what rejects it is the state the alert happens to be in, and that state
 * can change between one request and the next. Answering 400 would tell the caller to fix their
 * request, and there is nothing in it to fix.
 *
 * <p>The legal targets travel with it so the response can say what the caller may do instead. That
 * is a property of the graph rather than of this alert, so naming it discloses nothing.
 */
public class IllegalAlertTransitionException extends RuntimeException {

    private final UUID alertId;
    private final AlertStatus from;
    private final AlertStatus to;
    private final Set<AlertStatus> legalTargets;

    public IllegalAlertTransitionException(
            UUID alertId, AlertStatus from, AlertStatus to, Set<AlertStatus> legalTargets) {
        super("An alert in " + from + " cannot move to " + to
                + (legalTargets.isEmpty() ? ", because " + from + " is terminal" : "; it may move to " + legalTargets));
        this.alertId = alertId;
        this.from = from;
        this.to = to;
        this.legalTargets = Set.copyOf(legalTargets);
    }

    public UUID alertId() {
        return alertId;
    }

    public AlertStatus from() {
        return from;
    }

    public AlertStatus to() {
        return to;
    }

    public Set<AlertStatus> legalTargets() {
        return legalTargets;
    }
}
