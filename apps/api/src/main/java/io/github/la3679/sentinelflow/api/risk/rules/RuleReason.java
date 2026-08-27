/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.risk.rules;

import java.math.BigDecimal;

import io.github.la3679.sentinelflow.api.domain.ReasonSource;

/**
 * One rule that fired, and what it contributed.
 *
 * <p><strong>The contribution is the weight, and that is the whole appeal of a ruleset.</strong> A
 * rule's contribution is exactly the number configuration says it is — no calibration, no
 * standardisation, no averaging across folds — so an analyst defending a decision can add the
 * reasons up and get the score. A model's contributions explain a ranking and deliberately do not
 * sum to anything; these do, and the difference is why the degraded path is a real answer rather
 * than a placeholder.
 *
 * @param code the stable identifier. Never renamed once emitted.
 * @param description one sentence an analyst reads, generated from the rule and the value that
 *     tripped it rather than stored as free text.
 * @param contribution the weight, on the 0-to-100 scale. Always positive: a rule fires or it does
 *     not, and a rule that lowered a risk score would be an argument for innocence that nobody
 *     wrote.
 */
public record RuleReason(RuleCode code, String description, BigDecimal contribution) {

    /** Always {@link ReasonSource#RULE}. Stated as a method so no caller has to remember it. */
    public ReasonSource source() {
        return ReasonSource.RULE;
    }
}
