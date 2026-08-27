/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.scoring.export;

import java.math.BigDecimal;

import io.github.la3679.sentinelflow.api.risk.rules.RuleOutcome;
import io.github.la3679.sentinelflow.api.scoring.payload.AccountContext;
import io.github.la3679.sentinelflow.api.scoring.payload.ScoreRequest;
import io.github.la3679.sentinelflow.api.scoring.payload.TransactionToScore;
import io.github.la3679.sentinelflow.api.seed.scenario.ScenarioType;

/**
 * One line of the training dataset: a scoring request, and the shape it was planted as.
 *
 * <p><strong>The request half is exactly what the scoring service receives at runtime</strong> —
 * the same two fields, produced by the same assembler, in the same order. That is ADR-0010 §1's
 * requirement made concrete: a model is trained on the object it will be served, so there is no
 * representation gap for skew to live in.
 *
 * <p><strong>The label is the third field and it exists only here.</strong> {@link ScenarioType}
 * never enters the database — a label column on {@code transactions} would be information that only
 * exists after the fact, sitting next to the row a model is asked to score, which is the textbook
 * leak. It lives in this file and in the generator's memory, and nowhere else.
 *
 * <p><strong>No derived {@code suspicious} boolean.</strong> It would be redundant with
 * {@code label}, and two fields that must agree are two fields that can disagree. Which labels are
 * positive is stated once, in the manifest's {@code negativeLabel}, so the trainer reads it rather
 * than hard-coding {@code NORMAL} in a second language.
 *
 * <p><strong>{@code ruleScore} is computed by the ruleset that ships, and it is a comparison rather
 * than a feature.</strong> ADR-0010 §5 lets a model ship only if it beats the rules baseline by a
 * stated margin, which is only an honest gate if the baseline is the ruleset the API actually runs
 * when scoring is unavailable. The alternative — reimplementing the rules in Python for the
 * evaluation — is the same mistake ADR-0010 §1 rejects for the account context, with the same
 * failure mode: two implementations drift, and the drift shows up as a model beating a baseline
 * nobody runs. So the shipped engine evaluates every example as it is exported, exactly as it will
 * evaluate a live transaction, and the trainer reads the column.
 *
 * <p>It is not part of {@code ScoreRequest} and therefore cannot reach the feature extractor, which
 * builds its vector from the two request fields alone. That matters: a model trained on the rule
 * score would be partly modelling the rules, and beating them would then mean very little.
 */
public record TrainingExample(
        TransactionToScore transaction, AccountContext accountContext, ScenarioType label, BigDecimal ruleScore) {

    public static TrainingExample of(ScoreRequest request, ScenarioType label, RuleOutcome rules) {
        return new TrainingExample(request.transaction(), request.accountContext(), label, rules.score());
    }
}
