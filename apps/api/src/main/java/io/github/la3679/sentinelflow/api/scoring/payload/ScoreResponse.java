/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.scoring.payload;

import java.math.BigDecimal;
import java.util.List;

/**
 * What {@code POST /v1/score} returns.
 *
 * <p>Field-for-field with {@code contracts/openapi/sentinelflow-scoring.yaml}, which is
 * authoritative; {@code ScoringPayloadContractIT} asserts they have not drifted. The mirror of the
 * request-side risk applies here: a field the contract gained and this record did not is a value the
 * service sends and the API silently discards, and a discarded {@code featureVersion} is a score
 * nobody can attribute.
 *
 * <p><strong>{@code modelScore} is a decimal, never a double.</strong> It is persisted to
 * {@code NUMERIC(5,2)} and combined with a rule score in the same arithmetic, and money-adjacent
 * numbers do not enter this codebase as floating point (ADR-0007). Jackson binds the JSON number to
 * {@code BigDecimal} without going through a {@code double}.
 *
 * <p><strong>{@code warnings} is present and possibly empty, never absent.</strong> The contract
 * requires it so that no caller has to distinguish "no warnings" from "the field was left out".
 *
 * @param modelVersion persisted on the assessment; without it a score months old cannot be
 *     attributed to the model that produced it
 * @param featureVersion separate from the model version, because a feature definition can change
 *     under a model that did not
 * @param modelScore 0 to 100, higher is riskier. <strong>Not a probability</strong>, and
 *     deliberately not presented as one.
 * @param reasons bounded and most significant first
 * @param inferenceDurationMs measured by the scoring service, so the caller can tell a slow model
 *     from a slow network without guessing. Not the same number as the caller's own latency.
 * @param warnings what the service had to work around — a truncated context, a lookback shorter than
 *     a feature wanted
 */
public record ScoreResponse(
        String modelVersion,
        String featureVersion,
        BigDecimal modelScore,
        List<ReasonContribution> reasons,
        BigDecimal inferenceDurationMs,
        List<String> warnings) {

    public ScoreResponse {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
