/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.risk.rules;

import java.math.BigDecimal;
import java.util.List;

/**
 * What the ruleset concluded about one transaction.
 *
 * @param score the rule contribution on the contract's 0-to-100 scale, already clipped.
 * @param reasons the rules that fired, most significant first. Empty is the ordinary case — most
 *     transactions trip nothing — and an empty list with a score of zero is a complete answer rather
 *     than a missing one.
 * @param rulesetVersion which configuration produced this. Persisted on the assessment, because a
 *     rule score cannot be defended without it.
 */
public record RuleOutcome(BigDecimal score, List<RuleReason> reasons, String rulesetVersion) {

    public RuleOutcome {
        reasons = List.copyOf(reasons);
    }

    /** Whether anything fired. Not derived from the score, which can be zero only when nothing did. */
    public boolean isClean() {
        return reasons.isEmpty();
    }
}
