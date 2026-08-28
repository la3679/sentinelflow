/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.scoring.payload;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * What the scoring service says about the model it has loaded.
 *
 * <p>Field-for-field with {@code ModelInfo} in {@code contracts/openapi/sentinelflow-scoring.yaml},
 * which is what {@code GET /v1/model} answers.
 *
 * <p><strong>{@code @JsonIgnoreProperties(ignoreUnknown = true)}, unlike the score response.</strong>
 * A score is a decision this service persists and every field in it matters, so an unknown one is a
 * contract change that should be noticed. This is metadata for one read-only screen: a scoring
 * service that starts publishing an extra figure must not stop the API from answering a screen that
 * would simply not show it.
 *
 * <p>Every number is a {@link BigDecimal}, so Jackson binds the JSON number without a {@code double}
 * in between (ADR-0007). Nothing here is money, but a metric an operator reads and quotes deserves
 * the same treatment as one they act on.
 *
 * @param algorithm what kind of model it is, published so a screen need not infer it from a version
 * @param artifactSha256 the checksum of the artifact actually loaded, which is what makes
 *     "which model is running" answerable rather than assumed
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelInfoResponse(
        String modelVersion,
        String featureVersion,
        String algorithm,
        String trainedAt,
        String artifactSha256,
        String datasetFingerprint,
        Metrics metrics) {

    /**
     * What the model was measured at, on its own evaluation split, on synthetic data.
     *
     * <p><strong>Accuracy is absent here because it is absent there.</strong> Suspicious
     * transactions are extremely imbalanced, so a model that answers "not suspicious" to everything
     * scores well on accuracy and is worthless — and a field that exists is a field somebody quotes.
     *
     * @param operatingThreshold the model's own recommended operating point, which is
     *     <em>not</em> the threshold that ran: the API applies its own policy to a final score that
     *     also folds in a rule score this model never saw (ADR-0008 §4, ADR-0011).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Metrics(
            BigDecimal precision,
            BigDecimal recall,
            BigDecimal f1,
            BigDecimal averagePrecision,
            BigDecimal rocAuc,
            BigDecimal falsePositiveRate,
            BigDecimal operatingThreshold,
            Integer alertVolumeAtThreshold) {}
}
