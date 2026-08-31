/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A note to add to an alert's history.
 *
 * <p>{@code @NotBlank} because {@code alert_actions_note_present} refuses a {@code NOTE_ADDED} row
 * whose note is empty or whitespace, and a request that would violate a constraint should be
 * refused at the boundary with a message naming the field rather than at commit with a constraint
 * name.
 *
 * <p><strong>No {@code expectedVersion}.</strong> A note is appended rather than replacing
 * anything, so two analysts writing one at the same time both succeed and both notes are kept.
 * Demanding a version would refuse the second for no reason a user could act on.
 */
public record AlertNoteRequest(@NotBlank @Size(max = 2000) String note) {

    /**
     * That there is a note, never what it says.
     *
     * <p>Spring's {@code RequestResponseBodyMethodProcessor} logs {@code Read "application/json" to
     * […]} at {@code DEBUG}, rendering the deserialised record. ADR-0016 §4 forbids a whole request
     * body in a log at every level, and this body is nothing but an analyst's own words about an
     * alert. Without this override, raising the framework's own logger to {@code DEBUG} — which a
     * deployment may do while chasing something unrelated — writes every note into the log.
     *
     * <p>Found by widening {@code LogRedactionIT} to the alert workflow, which no earlier version of
     * it exercised.
     */
    @Override
    public String toString() {
        return "AlertNoteRequest[note redacted, " + (note == null ? 0 : note.length()) + " chars]";
    }
}
