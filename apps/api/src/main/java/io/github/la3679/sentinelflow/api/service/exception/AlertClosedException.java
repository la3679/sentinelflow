/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.service.exception;

import java.util.UUID;

import io.github.la3679.sentinelflow.api.domain.AlertStatus;

/**
 * The investigation is over, and this operation only makes sense while it is not.
 *
 * <p>Assigning finished work to somebody, or adding a note to a case nobody will read again, are
 * both operations whose meaning depends on the alert being live. Refusing them is not pedantry: an
 * assignee on a closed alert would appear in "what is on this analyst's desk" for ever, and a note
 * added after a disposition reads as though it informed one.
 *
 * <p><strong>A conflict, not a validation failure.</strong> The request is well formed; the alert
 * moved on. That is the same shape as an illegal transition and for the same reason — the state that
 * refuses it can change between one request and the next, so there is nothing in the request to fix.
 */
public class AlertClosedException extends RuntimeException {

    private final UUID alertId;
    private final AlertStatus status;

    public AlertClosedException(UUID alertId, AlertStatus status, String operation) {
        super(operation + " is not possible on an alert in " + status + ", which is a closed investigation");
        this.alertId = alertId;
        this.status = status;
    }

    public UUID alertId() {
        return alertId;
    }

    public AlertStatus status() {
        return status;
    }
}
