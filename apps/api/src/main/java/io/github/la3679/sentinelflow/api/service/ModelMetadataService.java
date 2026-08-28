/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.github.la3679.sentinelflow.api.domain.RiskBand;
import io.github.la3679.sentinelflow.api.risk.RiskPolicyProperties;
import io.github.la3679.sentinelflow.api.scoring.client.ScoringClient;
import io.github.la3679.sentinelflow.api.scoring.client.ScoringUnavailableException;
import io.github.la3679.sentinelflow.api.scoring.payload.ModelInfoResponse;
import io.github.la3679.sentinelflow.api.web.dto.ModelMetadataResponse;

/**
 * What is scoring, and what this service does with the score.
 *
 * <p>Composed from two owners (ADR-0014 §1). The model half belongs to the scoring service, which
 * loads the artifact and is the only thing that can say what is actually running; the policy half is
 * this service's, which ADR-0008 §4 gives the alerting policy. Neither can answer for the other.
 *
 * <p><strong>Not read from {@code model_registry}.</strong> That table has never had a row written
 * to it, so an endpoint over it would answer a permanent 404 that looked like an outage. Populating
 * it would mean deciding this service is the registry of record, which needs a promotion path, an
 * audit trail and a rollback story — none of which exists, and all of which is more than a read
 * screen should drag in. ADR-0014 records it as debt.
 */
@Service
public class ModelMetadataService {

    private static final Logger log = LoggerFactory.getLogger(ModelMetadataService.class);

    /**
     * Said on the screen rather than only in a model card nobody has open.
     *
     * <p>Constant rather than configurable: these are properties of what this project <em>is</em>,
     * and a caveat an operator can switch off is one that will be.
     */
    private static final List<String> LIMITATIONS = List.of(
            "Every figure here describes a synthetic demonstration model. No production or"
                    + " real-world performance is represented.",
            "A score is not a determination of fraud. It is an ordering signal for human review.",
            "The model's own operating threshold is a recommendation. What runs is the policy below,"
                    + " applied to a final score that also folds in a rule score the model never saw.",
            "A transaction that trips no transparent rule cannot reach the alerting band however"
                    + " confident the model is. That is a stated policy, not an accident — see ADR-0011 §4.",
            "Thresholds are read-only here. Changing one is a configuration change to the service,"
                    + " deliberately not an operation this API exposes.");

    private final ScoringClient scoring;
    private final RiskPolicyProperties policy;

    public ModelMetadataService(ScoringClient scoring, RiskPolicyProperties policy) {
        this.scoring = scoring;
        this.policy = policy;
    }

    /**
     * The active model's metadata, with the policy that decides what is done with its scores.
     *
     * <p><strong>Never throws when scoring cannot be reached.</strong> The policy half is always
     * knowable and is half of what the screen is for, so an unreachable scoring service produces an
     * answer that says so rather than a failure. The console renders a degraded assessment the same
     * way, for the same reason.
     */
    public ModelMetadataResponse active(UUID correlationId) {
        try {
            return compose(scoring.modelInfo(correlationId), null);
        } catch (ScoringUnavailableException unavailable) {
            // Info rather than warn. The scoring service being unreachable is
            // already logged where it matters - at the assessments that degraded
            // because of it - and a screen refresh is not a second incident.
            log.info("Model metadata is unavailable; answering with the policy alone: {}", unavailable.getMessage());
            return compose(null, unavailable.getMessage());
        }
    }

    private ModelMetadataResponse compose(ModelInfoResponse model, String unavailableReason) {
        List<ModelMetadataResponse.Threshold> thresholds = new ArrayList<>();
        for (RiskBand band : RiskBand.values()) {
            boolean alerts = policy.raisesAlert(band);
            thresholds.add(new ModelMetadataResponse.Threshold(
                    band, policy.bandLowerBounds().get(band), alerts, alerts ? policy.priorityFor(band) : null));
        }

        return new ModelMetadataResponse(
                model == null ? null : model.modelVersion(),
                model == null ? null : model.featureVersion(),
                policy.version(),
                model == null ? null : model.algorithm(),
                model == null ? null : model.trainedAt(),
                model == null ? null : model.artifactSha256(),
                model != null,
                unavailableReason,
                model == null || model.metrics() == null ? null : metricsOf(model.metrics()),
                List.copyOf(thresholds),
                LIMITATIONS);
    }

    private static ModelMetadataResponse.Metrics metricsOf(ModelInfoResponse.Metrics metrics) {
        return new ModelMetadataResponse.Metrics(
                metrics.precision(),
                metrics.recall(),
                metrics.f1(),
                metrics.averagePrecision(),
                metrics.falsePositiveRate(),
                metrics.operatingThreshold());
    }
}
