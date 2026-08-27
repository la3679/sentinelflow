/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.domain;

import java.math.BigDecimal;

/**
 * One contributing factor behind a score, with how much it contributed and where it came from.
 *
 * <p>Field-for-field with {@code reasonCode} in {@code contracts/schemas/common.v1.json} and
 * {@code ReasonCode} in {@code contracts/openapi/sentinelflow-api.yaml}, which are what
 * {@code risk.assessed.v1} and the read API carry.
 *
 * <p><strong>This shape is a correction.</strong> {@code risk_assessments.reason_codes} was mapped as
 * a list of bare strings from Phase 2 until the assessment workflow landed, while both contracts had
 * always described an object. Nothing noticed because nothing wrote the column: the first write was
 * also the first time the two had to agree. The column is {@code jsonb}, so the fix needed no
 * migration — which is exactly the flexibility that let the mismatch sit there unseen.
 *
 * @param code stable and machine-readable. <strong>Never renamed once emitted</strong> — a renamed
 *     code silently breaks every historical query and every saved analyst filter.
 * @param description one sentence for an analyst, generated from the rule and the value that tripped
 *     it rather than stored as free text. An analyst defending a decision months later has the
 *     sentence, not just the code.
 * @param contribution how much this pushed the score. For a rule it is the configured weight and the
 *     reasons sum to the rule score; for a model it is the estimator's own decomposition on its own
 *     scale, which explains the ranking and deliberately does not sum to anything.
 * @param source which of the two it was. An analyst needs to know: a rule can be read, and a model
 *     score can only be attributed.
 */
public record ReasonCode(String code, String description, BigDecimal contribution, ReasonSource source) {

    /**
     * What an assessment says when nothing fired.
     *
     * <p>{@code risk_assessments_reason_codes_shape} requires at least one reason, and its comment
     * says why: "an assessment with no reason at all cannot be defended to anyone". A quiet
     * transaction is the ordinary case, so something has to be said about it — and "the ruleset
     * examined this and found nothing" is a real explanation, where an empty array is the absence of
     * one.
     *
     * <p>Sourced to the rules because the rules are what examined it. Contribution zero, because that
     * is what it contributed.
     */
    public static ReasonCode noIndicators() {
        return new ReasonCode(
                "NO_INDICATORS",
                "No rule indicator fired and the model attributed nothing to any feature.",
                BigDecimal.ZERO,
                ReasonSource.RULE);
    }
}
