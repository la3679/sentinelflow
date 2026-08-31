/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.github.la3679.sentinelflow.api.domain.FeedbackLabel;

/**
 * An analyst's verdict on the decision behind an alert.
 *
 * <p><strong>No {@code expectedVersion}.</strong> This is the actor's own row, and nobody else can
 * change it: {@code analyst_feedback_unique} is per assessment <em>and</em> per actor, so there is
 * no concurrent writer to lose a race against. Two analysts labelling the same assessment
 * differently is not a conflict — it is two opinions, and both are kept.
 *
 * @param label the verdict. {@code INCONCLUSIVE} exists so somebody who cannot tell is not forced to
 *     guess, because training on a coerced label is worse than training on fewer of them.
 * @param reason why, in the analyst's own words. Optional, and stored rather than interpreted.
 */
public record AlertFeedbackRequest(
        @NotNull FeedbackLabel label, @Size(max = 1000) String reason) {

    /**
     * The verdict, never the sentence behind it.
     *
     * <p>The same rule as {@link AlertNoteRequest}: an analyst's own words are a request body, and
     * the framework renders a deserialised body at {@code DEBUG}. The label is the part a log line
     * about feedback has any use for.
     */
    @Override
    public String toString() {
        return "AlertFeedbackRequest[label=" + label + " reason redacted]";
    }
}
