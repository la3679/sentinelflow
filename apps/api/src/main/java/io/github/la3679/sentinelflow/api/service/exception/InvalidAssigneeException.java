/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.service.exception;

import java.util.UUID;

/**
 * The alert cannot be given to this person.
 *
 * <p>Three reasons, all of which mean the same thing to a caller: no such user, an account that has
 * been disabled, and a user whose roles do not let them work an alert. An auditor is read-only
 * (ADR-0012 §4), so assigning one a queue item would create work they are not permitted to do and
 * would show on a dashboard as unstarted for ever.
 *
 * <p>422 rather than 404: the alert exists and the request is well formed. What is wrong is a value
 * in the payload, which is what a 422 says.
 *
 * <p>The reason is carried for the response, and it is deliberately about the <em>assignee</em>
 * rather than about the directory. It says a user cannot be assigned work, never which users exist.
 */
public class InvalidAssigneeException extends RuntimeException {

    private final UUID assigneeId;

    public InvalidAssigneeException(UUID assigneeId, String reason) {
        super("User " + assigneeId + " cannot be assigned an alert: " + reason);
        this.assigneeId = assigneeId;
    }

    public UUID assigneeId() {
        return assigneeId;
    }
}
