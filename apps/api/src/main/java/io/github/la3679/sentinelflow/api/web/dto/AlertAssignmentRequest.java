/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * A request to give an alert to somebody, or to take it back.
 *
 * <p><strong>{@code assigneeId} is nullable and that is the release.</strong> One endpoint for both
 * directions, because the alert holds one assignee either way and a second endpoint would be a
 * second place the same rule about closed alerts had to be applied. Null means "back to the queue",
 * which the audit trail records as {@code UNASSIGNED} rather than as an assignment to nobody.
 *
 * @param assigneeId who to give it to, or null to release it
 * @param expectedVersion the version the caller believes the alert is at. Required, like every other
 *     mutation: an optional one would make the safe call the longer one to write.
 * @param note why, in the actor's own words. Optional, and stored on the history row this writes.
 */
public record AlertAssignmentRequest(
        UUID assigneeId,
        @NotNull @PositiveOrZero Long expectedVersion,
        @Size(max = 2000) String note) {

    /**
     * Who it is going to and which version was expected. Never the note.
     *
     * <p>The same rule as {@link AlertNoteRequest}. The assignee identifier stays: it is an operator
     * identifier rather than a secret, and it is how a contested assignment is followed.
     */
    @Override
    public String toString() {
        return "AlertAssignmentRequest[assigneeId=" + assigneeId + " expectedVersion=" + expectedVersion
                + " note redacted]";
    }
}
