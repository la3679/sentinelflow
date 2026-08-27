/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.scoring.export;

import java.time.Instant;
import java.util.Map;

import io.github.la3679.sentinelflow.api.seed.scenario.ScenarioType;

/**
 * What produced a training dataset, written beside it.
 *
 * <p>ADR-0010 §1 says the dataset is regenerated rather than committed, which only works if
 * everything needed to regenerate it is recorded. This is that record, and it is also half of the
 * training manifest the model registry will carry: the dataset fingerprint a rerun is compared
 * against.
 *
 * @param generatorVersion which generator drew the shapes. A shape redefined under an unchanged
 *     seed produces different data from the same number, so the seed alone does not identify a
 *     dataset.
 * @param seed the value that makes this reproducible. Quote it; pin it.
 * @param profile how much data — {@code CI}, {@code DEMO} or {@code LOCAL}.
 * @param exportedAt when this export ran. Provenance, not an input: it is deliberately the only
 *     value here that comes from a clock, and nothing in the dataset depends on it. The generated
 *     window's own end is not recorded because the export cannot know it and does not need it —
 *     every timestamp in the dataset comes from the stored rows rather than from regeneration, and
 *     the idempotency keys the join uses derive from offsets within the window rather than from
 *     absolute time.
 * @param contextVersion the shape of the account context every example carries.
 * @param lookbackWindowSeconds how much history each example was given. A dataset built with an
 *     hour of lookback and one built with a day are different datasets, whatever else matches.
 * @param exported how many labelled examples the file holds.
 * @param generated how many the generator produced. Lower than {@code exported} is impossible;
 *     higher means rows the export could not find in the database, which is reported rather than
 *     rounded off.
 * @param distribution counts per shape. The class balance, stated, because every metric in the
 *     evaluation report is meaningless without it.
 * @param negativeLabel which label is the negative class. Stated here rather than assumed by the
 *     trainer, so {@code NORMAL} is not a magic string in two languages that can drift apart.
 * @param rulesetVersion which ruleset produced the {@code ruleScore} on every example. Recorded
 *     because the model-selection gate is a margin over that baseline (ADR-0010 §5), and a margin
 *     over an unnamed baseline is not a result anyone can reproduce.
 * @param scenarioChecksum SHA-256 over what the generator described. Two runs producing the same
 *     value produced the same data.
 * @param datasetSha256 SHA-256 over the dataset file's bytes. This is the fingerprint the model
 *     registry stores: it covers the assembled contexts as well as the generated transactions, so
 *     it changes when the assembler changes even though the generator did not.
 */
public record TrainingExportManifest(
        String generatorVersion,
        long seed,
        String profile,
        Instant exportedAt,
        int contextVersion,
        long lookbackWindowSeconds,
        int exported,
        int generated,
        Map<ScenarioType, Integer> distribution,
        ScenarioType negativeLabel,
        String rulesetVersion,
        String scenarioChecksum,
        String datasetSha256) {

    /**
     * The only shape that is not a planted suspicious pattern.
     *
     * <p>Named as a constant rather than written inline so that adding a shape to
     * {@link ScenarioType} cannot silently change which class is positive.
     */
    public static final ScenarioType NEGATIVE_LABEL = ScenarioType.NORMAL;

    /** How many examples carry a planted shape rather than ordinary background traffic. */
    public int planted() {
        return exported - distribution.getOrDefault(NEGATIVE_LABEL, 0);
    }
}
