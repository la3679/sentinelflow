/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web.dto;

import java.math.BigDecimal;
import java.util.List;

import io.github.la3679.sentinelflow.api.domain.AlertPriority;
import io.github.la3679.sentinelflow.api.domain.RiskBand;

/**
 * What is scoring, and what is done with the score.
 *
 * <p>Field-for-field with the {@code ModelMetadata} schema in {@code contracts/openapi/}. Two halves
 * from two owners, composed here because ADR-0002 makes this API the only backend the console talks
 * to: the model half comes from the scoring service, which loads the artifact and is therefore the
 * only thing that can say what is actually running, and the policy half from this service, which
 * ADR-0008 §4 gives "the alerting policy applied to a final score at runtime".
 *
 * <p><strong>The model half is nullable and the policy half is not.</strong> A scoring service that
 * is restarting or has no artifact yet must not blank this screen: the bands, the alerting threshold
 * and the priorities are this service's own and are always knowable. {@code modelAvailable} says
 * which case a reader is looking at, rather than leaving them to infer it from a null.
 *
 * <p>Accuracy is absent, here and in both contracts, because the classes are extremely imbalanced —
 * a model that answers "not suspicious" to everything scores well on it and is worthless. A figure
 * that exists is a figure somebody quotes.
 */
public record ModelMetadataResponse(
        String modelVersion,
        String featureVersion,
        String policyVersion,
        String algorithm,
        String trainedAt,
        String artifactSha256,
        boolean modelAvailable,
        String modelUnavailableReason,
        Metrics metrics,
        List<Threshold> thresholds,
        List<String> limitations) {

    /** What the model was measured at, on its own synthetic evaluation split. */
    public record Metrics(
            BigDecimal precision,
            BigDecimal recall,
            BigDecimal f1,
            BigDecimal averagePrecision,
            BigDecimal falsePositiveRate,
            BigDecimal operatingThreshold) {}

    /**
     * One band, the score it starts at, and what happens to a transaction in it.
     *
     * <p>Built from {@code RiskPolicyProperties}, which validates the bounds at startup — so this is
     * a projection of the policy that actually ran rather than a table maintained beside it.
     */
    public record Threshold(RiskBand riskBand, BigDecimal minFinalScore, boolean raisesAlert, AlertPriority priority) {}
}
