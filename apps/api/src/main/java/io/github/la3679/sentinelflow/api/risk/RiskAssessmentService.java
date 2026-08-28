/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.risk;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.la3679.sentinelflow.api.domain.EventType;
import io.github.la3679.sentinelflow.api.domain.ProcessingStatus;
import io.github.la3679.sentinelflow.api.domain.ReasonCode;
import io.github.la3679.sentinelflow.api.domain.ReasonSource;
import io.github.la3679.sentinelflow.api.domain.RiskBand;
import io.github.la3679.sentinelflow.api.messaging.payload.RiskAssessedPayload;
import io.github.la3679.sentinelflow.api.persistence.entity.OutboxEvent;
import io.github.la3679.sentinelflow.api.persistence.entity.RiskAssessment;
import io.github.la3679.sentinelflow.api.persistence.entity.TransactionRecord;
import io.github.la3679.sentinelflow.api.persistence.repository.OutboxEventRepository;
import io.github.la3679.sentinelflow.api.persistence.repository.RiskAssessmentRepository;
import io.github.la3679.sentinelflow.api.risk.rules.RuleEngine;
import io.github.la3679.sentinelflow.api.risk.rules.RuleOutcome;
import io.github.la3679.sentinelflow.api.risk.rules.RuleReason;
import io.github.la3679.sentinelflow.api.scoring.AccountContextAssembler;
import io.github.la3679.sentinelflow.api.scoring.client.ScoringClient;
import io.github.la3679.sentinelflow.api.scoring.client.ScoringRejectedException;
import io.github.la3679.sentinelflow.api.scoring.client.ScoringResult;
import io.github.la3679.sentinelflow.api.scoring.client.ScoringUnavailableException;
import io.github.la3679.sentinelflow.api.scoring.payload.ReasonContribution;
import io.github.la3679.sentinelflow.api.scoring.payload.ScoreRequest;
import io.github.la3679.sentinelflow.api.scoring.payload.ScoreResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The workflow that turns a transaction into a decision somebody can defend.
 *
 * <p>Every part of it already existed and none of them were joined: the account context assembler
 * builds the request, the rule engine scores it in-process, the scoring client calls the model
 * inside ADR-0008 §3's budget, and {@link RiskPolicyProperties} combines and bands the result under
 * ADR-0011. This is the one method that runs them in order and writes what came out.
 *
 * <h2>Three outcomes, mapped straight onto ADR-0008 §2's table</h2>
 *
 * <ul>
 *   <li>Scoring answers — {@code RiskAssessment.scored(...)}, with the model's score, its versions
 *       and the caller-measured latency.
 *   <li>Scoring is unreachable past its budget, or the breaker is open — {@code degraded(...)},
 *       scored by the rules alone. A real answer produced in this process, which is the whole
 *       reason the ruleset lives on this side of the boundary.
 *   <li>Scoring rejects the request — {@link ScoringRejectedException} propagates, untouched. It is
 *       not caught here and it is emphatically not degraded: two services in one repository
 *       disagreeing about a contract is a defect to fix, and absorbing it would hide it behind a
 *       system that still looks healthy. The messaging layer is what turns it into a dead letter,
 *       because "dead letter" is a delivery concept and this class is not about delivery.
 * </ul>
 *
 * <h2>One transaction, three writes</h2>
 *
 * The assessment, the transaction's status, and the outbox row commit together or not at all. That
 * is not tidiness: the caller is {@code IdempotentEventProcessor}, whose ledger row is in the same
 * transaction, so "this event was processed" and "the assessment exists" are one fact rather than
 * two that usually agree. A failure anywhere rolls the claim back with it and the redelivery is
 * genuinely a first attempt.
 *
 * <p><strong>The event goes through the outbox, like every other event.</strong> Publishing to Kafka
 * from here would be a second commit with a window in it, and every crash in that window either
 * loses the event or announces an assessment that rolled back.
 *
 * <h2>What this does not do</h2>
 *
 * <p><strong>It raises alerts through {@link AlertRaiser}, and decides nothing about them.</strong>
 * {@link RiskPolicyProperties} says which bands are worth a person's time (ADR-0008 §4), the two
 * methods below record that answer on the assessment as {@code alertRaised} at the moment they
 * compute the band, and the raiser acts on it in the same transaction. The flag and the alert come
 * from one decision rather than two that agree: asking the policy again after the row was built
 * would mean that the day they disagreed, an assessment would claim an alert nobody could find.
 *
 * <p><strong>It does not rescore.</strong> Every assessment is version 1. Rescoring under a new
 * policy is a deliberate, audited operation like the other recovery paths in this system (ADR-0005
 * §5, ADR-0008 §2), it allocates the next version rather than overwriting, and none of it exists
 * yet. A method that quietly incremented the version would make an automatic upgrade one call away
 * from happening by accident.
 */
@Service
public class RiskAssessmentService {

    private static final Logger log = LoggerFactory.getLogger(RiskAssessmentService.class);

    /**
     * The version every assessment this workflow writes carries.
     *
     * <p>A constant rather than a query for the next free number. Nothing rescores, and computing a
     * successor here would put the mechanism for it in place without the audit trail ADR-0005 §5
     * requires around it.
     */
    static final int FIRST_ASSESSMENT_VERSION = 1;

    /** The schema version of the payload this publishes. Bumped only alongside a v2 payload schema. */
    private static final int RISK_ASSESSED_SCHEMA_VERSION = 1;

    /**
     * What {@code risk_assessments_reason_codes_shape} allows, applied before the insert rather
     * than discovered by it.
     *
     * <p>It cannot bind today: seven rules and a scoring contract that caps its own list at ten come
     * to seventeen. It is here because the alternative to a cap the code applies is a constraint
     * violation at commit — which, inside a Kafka consumer, is an exception on every redelivery of a
     * record that will never succeed, with the partition queued behind it.
     */
    static final int MAX_REASON_CODES = 20;

    /**
     * The scale a model contribution is described at in its generated sentence.
     *
     * <p>Four places, because these are log-odds contributions clustered near zero and two would
     * round a real difference between two features to the same printed number. It is only the
     * sentence: {@code contribution} carries the value the model produced.
     */
    private static final int MODEL_CONTRIBUTION_SCALE = 4;

    private final AccountContextAssembler assembler;
    private final RuleEngine rules;
    private final ScoringClient scoring;
    private final RiskPolicyProperties policy;
    private final RiskAssessmentRepository assessments;
    private final AlertRaiser alertRaiser;
    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meters;

    public RiskAssessmentService(
            AccountContextAssembler assembler,
            RuleEngine rules,
            ScoringClient scoring,
            RiskPolicyProperties policy,
            RiskAssessmentRepository assessments,
            AlertRaiser alertRaiser,
            OutboxEventRepository outbox,
            ObjectMapper objectMapper,
            MeterRegistry meters) {
        this.assembler = assembler;
        this.rules = rules;
        this.scoring = scoring;
        this.policy = policy;
        this.assessments = assessments;
        this.alertRaiser = alertRaiser;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.meters = meters;
    }

    /**
     * Assess one transaction and persist the decision, with the event that announces it.
     *
     * @param transaction a managed entity. Its {@code processingStatus} moves to {@code ASSESSED} by
     *     dirty checking rather than by a second query, which is what keeps the status and the
     *     assessment in one commit.
     * @param correlationId ties this to the originating request, the outbox row that published the
     *     transaction, the consumer record, and the scoring call
     * @throws ScoringRejectedException if the scoring service refused the request. Deliberately not
     *     caught: see the class comment.
     */
    @Transactional
    public RiskAssessment assess(TransactionRecord transaction, UUID correlationId) {
        ScoreRequest request = assembler.assemble(transaction);
        RuleOutcome ruleOutcome = rules.evaluate(request);

        // One instant for the assessment, taken before the call rather than
        // after it: assessedAt is when this transaction was assessed, and a
        // degraded assessment produced after a three-attempt budget would
        // otherwise be stamped seconds later than an identical one produced
        // when the service was up.
        Instant assessedAt = Instant.now();

        RiskAssessment assessment;
        try {
            assessment = scoredAssessment(
                    transaction.getId(), ruleOutcome, scoring.score(request, correlationId), assessedAt);
        } catch (ScoringUnavailableException unavailable) {
            // Expected and survivable. The warn that matters was logged by the
            // client when the budget ran out or the breaker opened; repeating it
            // per record is the noise the breaker exists to avoid.
            log.debug("Scoring did not answer for transaction {}; assessing on rules alone", transaction.getId());
            assessment = degradedAssessment(transaction.getId(), ruleOutcome, assessedAt);
        }

        assessments.save(assessment);
        transaction.setProcessingStatus(ProcessingStatus.ASSESSED);
        outbox.save(outboxEventFor(assessment, request, transaction.getAccountId(), correlationId));

        // The flag and the alert come from one decision, taken inside the two
        // methods above where the band is computed. Asking the policy a second
        // time here would be two answers to one question, and the day they
        // disagreed the row would say an alert was raised that nobody could
        // find.
        if (assessment.isAlertRaised()) {
            alertRaiser.raise(assessment, transaction, correlationId, assessedAt);
        }

        count(assessment);
        return assessment;
    }

    /**
     * The scored shape: the rules' floor, the model's escalation, and every version that produced
     * either.
     *
     * <p>Package-private and pure over its arguments, so the arithmetic and the reason assembly are
     * testable without a database, a broker or an HTTP server. What is left in {@link #assess} is
     * the ordering and the writes, which is what the integration test is for.
     */
    RiskAssessment scoredAssessment(
            UUID transactionId, RuleOutcome ruleOutcome, ScoringResult result, Instant assessedAt) {
        ScoreResponse response = result.response();
        BigDecimal modelScore = policy.onContractScale(response.modelScore());
        BigDecimal finalScore = policy.combine(ruleOutcome.score(), modelScore);
        RiskBand band = policy.bandFor(finalScore);

        return RiskAssessment.scored(
                transactionId,
                FIRST_ASSESSMENT_VERSION,
                ruleOutcome.score(),
                modelScore,
                finalScore,
                band,
                response.modelVersion(),
                response.featureVersion(),
                ruleOutcome.rulesetVersion(),
                policy.version(),
                reasonCodes(ruleOutcome, response),
                latencyColumnValue(result.latencyMs()),
                policy.raisesAlert(band),
                assessedAt);
    }

    /**
     * The degraded shape: the rule score unchanged, and every model-derived field absent rather than
     * defaulted.
     *
     * <p>A zero model score would be a claim about the transaction, and no such claim was made.
     * {@code risk_assessments_degraded_consistent} enforces the same thing at the column level; this
     * is the only way to construct the row in the first place.
     */
    RiskAssessment degradedAssessment(UUID transactionId, RuleOutcome ruleOutcome, Instant assessedAt) {
        BigDecimal finalScore = policy.combine(ruleOutcome.score());
        RiskBand band = policy.bandFor(finalScore);

        return RiskAssessment.degraded(
                transactionId,
                FIRST_ASSESSMENT_VERSION,
                ruleOutcome.score(),
                finalScore,
                band,
                ruleOutcome.rulesetVersion(),
                policy.version(),
                reasonCodes(ruleOutcome, null),
                policy.raisesAlert(band),
                assessedAt);
    }

    /**
     * The reasons, rules first.
     *
     * <p>Grouped by source rather than sorted as one list, which is what
     * {@code risk-assessed.v1.json} now says and why. A rule's contribution is a weight on the
     * 0-to-100 scale and the rule reasons sum to the rule score; a model's is a log-odds
     * decomposition that explains a ranking and sums to nothing. Interleaving them by magnitude
     * would rank a weight of 10 against a contribution of 0.4 as though the comparison meant
     * something.
     *
     * <p>Rules lead because they are the half an analyst can check. Within the model's own reasons
     * the order is by descending absolute contribution, then by code — the same tie-break the rule
     * engine applies, and for the same reason: an order that moved between identical runs would make
     * a persisted assessment unreproducible for no reason at all.
     *
     * @param response null on the degraded path, where there are no model reasons because there was
     *     no model answer
     */
    private List<ReasonCode> reasonCodes(RuleOutcome ruleOutcome, ScoreResponse response) {
        List<ReasonCode> combined = new ArrayList<>();
        ruleOutcome.reasons().stream()
                // Ordered here rather than trusted from the outcome. RuleEngine
                // already sorts its own reasons, and re-applying it is the same
                // argument the engine makes about re-windowing history: a
                // RuleOutcome can legitimately be built elsewhere — the labelled
                // export builds one — and the order this method emits is the one
                // the event contract describes, so this is the place that has to
                // make it true.
                .sorted(Comparator.comparing(RuleReason::contribution)
                        .reversed()
                        .thenComparing(reason -> reason.code().name()))
                .forEach(reason -> combined.add(new ReasonCode(
                        reason.code().name(), reason.description(), reason.contribution(), reason.source())));

        if (response != null) {
            response.reasons().stream()
                    .sorted(Comparator.comparing((ReasonContribution reason) ->
                                    reason.contribution().abs())
                            .reversed()
                            .thenComparing(ReasonContribution::code))
                    // Every rule reason is kept and the model's fill what is
                    // left. If the cap ever binds, dropping a rule reason would
                    // make the rule arithmetic stop adding up, and an analyst
                    // checking it would find a number they could not reproduce.
                    .limit(Math.max(0, MAX_REASON_CODES - combined.size()))
                    .forEach(reason -> combined.add(modelReason(reason)));
        }

        // "The ruleset examined this and found nothing" is an explanation. An
        // empty array is the absence of one, and the column's CHECK says so.
        return combined.isEmpty() ? List.of(ReasonCode.noIndicators()) : List.copyOf(combined);
    }

    /**
     * One model contribution as something an analyst reads.
     *
     * <p>The sentence is generated rather than stored, and it says what the number is as well as
     * what it was: a contribution on the model's own scale is not points added to the final score,
     * and an analyst shown {@code +0.4213} beside a rule's {@code 25} would otherwise reasonably
     * assume they were the same kind of quantity.
     */
    private static ReasonCode modelReason(ReasonContribution reason) {
        BigDecimal contribution = reason.contribution();
        String printed = contribution
                .setScale(MODEL_CONTRIBUTION_SCALE, RoundingMode.HALF_UP)
                .toPlainString();
        return new ReasonCode(
                reason.code(),
                "The model attributed %s%s to %s. Model contributions are on the model's own scale: they explain the ranking and do not sum to the score."
                        .formatted(contribution.signum() < 0 ? "" : "+", printed, reason.code()),
                contribution,
                ReasonSource.MODEL);
    }

    /**
     * The caller-measured latency, as the column stores it.
     *
     * <p>{@code scoring_latency_ms} is an {@code integer} and the client measures a {@code long}. The
     * whole call budget is under ten seconds by construction (ADR-0008 §3) so the narrowing cannot
     * lose anything, but a cast that is only correct because of a number in another file is a cast
     * worth bounding where it happens.
     */
    private static int latencyColumnValue(long latencyMs) {
        return Math.clamp(latencyMs, 0, Integer.MAX_VALUE);
    }

    private OutboxEvent outboxEventFor(
            RiskAssessment assessment, ScoreRequest request, UUID accountId, UUID correlationId) {
        return new OutboxEvent(
                EventType.RISK_ASSESSED,
                assessment.getId(),
                RISK_ASSESSED_SCHEMA_VERSION,
                // Keyed by the account, exactly as transaction.created.v1 is.
                // Kafka orders only within a partition, so keying an assessment
                // by its own identifier would let a consumer see an account's
                // assessments in an order its transactions never happened in.
                request.transaction().accountReference(),
                serialise(RiskAssessedPayload.of(assessment, accountId)),
                correlationId,
                // Trace context arrives with OpenTelemetry in Phase 7. Null
                // rather than a fabricated identifier: the column is nullable
                // precisely so this can be honest.
                null,
                assessment.getAssessedAt());
    }

    private String serialise(RiskAssessedPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            // Unreachable for a record of strings, decimals, enums and instants.
            // If it ever happens the assessment must not commit with an
            // unpublishable event beside it, so this throws rather than storing
            // a placeholder the relay would choke on later.
            throw new IllegalStateException("Cannot serialise a risk.assessed payload", e);
        }
    }

    /**
     * One counter per assessment, tagged by outcome and band.
     *
     * <p>The degraded rate is the operational number this workflow exists to make visible: a scoring
     * outage never rejects a transaction and never stops the pipeline, so without this the only
     * symptom is assessments that are quietly worse than they look. Band is four values, so the
     * cardinality is bounded and stays bounded.
     */
    private void count(RiskAssessment assessment) {
        RiskBand band = assessment.getRiskBand();
        Counter.builder("sentinelflow.risk.assessments")
                .tag("outcome", assessment.isDegraded() ? "degraded" : "scored")
                .tag("band", band.name())
                .description("Risk assessments written, by whether the model contributed and where they banded")
                .register(meters)
                .increment();
    }
}
