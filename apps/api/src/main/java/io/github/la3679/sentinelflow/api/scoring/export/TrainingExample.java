/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.scoring.export;

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
 */
public record TrainingExample(TransactionToScore transaction, AccountContext accountContext, ScenarioType label) {

    public static TrainingExample of(ScoreRequest request, ScenarioType label) {
        return new TrainingExample(request.transaction(), request.accountContext(), label);
    }
}
