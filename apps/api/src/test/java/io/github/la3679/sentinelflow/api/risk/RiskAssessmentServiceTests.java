/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.github.la3679.sentinelflow.api.domain.ReasonCode;
import io.github.la3679.sentinelflow.api.domain.ReasonSource;
import io.github.la3679.sentinelflow.api.domain.RiskBand;
import io.github.la3679.sentinelflow.api.persistence.entity.RiskAssessment;
import io.github.la3679.sentinelflow.api.risk.rules.RuleCode;
import io.github.la3679.sentinelflow.api.risk.rules.RuleOutcome;
import io.github.la3679.sentinelflow.api.risk.rules.RuleReason;
import io.github.la3679.sentinelflow.api.scoring.client.ScoringResult;
import io.github.la3679.sentinelflow.api.scoring.payload.ReasonContribution;
import io.github.la3679.sentinelflow.api.scoring.payload.ScoreResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * What the workflow decides, separated from what it writes.
 *
 * <p>{@code scoredAssessment} and {@code degradedAssessment} are pure over their arguments, which is
 * why they are their own methods: the combination, the banding, the reason assembly and the shape of
 * each row can be pinned here without a database, a broker or an HTTP server. The ordering, the
 * three writes and the failure paths are emergent and belong to
 * {@code RiskAssessmentServiceIT}, which has all three.
 *
 * <p>The collaborators that matter are real. {@link RiskPolicyProperties} does the arithmetic and a
 * stub of it would only assert that this class calls something.
 */
class RiskAssessmentServiceTests {

    private static final UUID TRANSACTION = UUID.randomUUID();
    private static final Instant ASSESSED_AT = Instant.parse("2026-08-27T02:30:00Z");

    private final RiskAssessmentService service =
            new RiskAssessmentService(null, null, null, policy(), null, null, null, new SimpleMeterRegistry());

    // ----------------------------------------------------------------------- //
    // The scored shape
    // ----------------------------------------------------------------------- //

    @Nested
    @DisplayName("when scoring answered")
    class Scored {

        @Test
        @DisplayName("the model raises the score above the rules' floor, and both are recorded")
        void combinesAndRecordsBothHalves() {
            RiskAssessment assessment = service.scoredAssessment(
                    TRANSACTION,
                    rules(new BigDecimal("25.00"), RuleCode.VELOCITY_5M_HIGH),
                    scored("90.00"),
                    ASSESSED_AT);

            // 0.6 x 90 + 0.4 x 25 = 64, above the floor of 25.
            assertThat(assessment.getFinalScore()).isEqualByComparingTo("64.00");
            assertThat(assessment.getRuleScore()).isEqualByComparingTo("25.00");
            assertThat(assessment.getModelScore()).isEqualByComparingTo("90.00");
            assertThat(assessment.getRiskBand()).isEqualTo(RiskBand.MEDIUM);
            assertThat(assessment.isDegraded()).isFalse();
        }

        @Test
        @DisplayName("every version that contributed is on the row")
        void carriesAllFourVersions() {
            RiskAssessment assessment = service.scoredAssessment(
                    TRANSACTION,
                    rules(new BigDecimal("25.00"), RuleCode.VELOCITY_5M_HIGH),
                    scored("90.00"),
                    ASSESSED_AT);

            assertThat(assessment.getModelVersion()).isEqualTo("2.1.0");
            assertThat(assessment.getFeatureVersion()).isEqualTo("1.0.0");
            assertThat(assessment.getRulesetVersion()).isEqualTo("1.0.0");
            assertThat(assessment.getPolicyVersion())
                    .as("an assessment that cannot name the policy that produced it cannot be "
                            + "defended months later")
                    .isEqualTo("1.0.0");
        }

        @Test
        @DisplayName("a model score the column would round is rounded before it is stored")
        void modelScoreIsPutOnTheContractScale() {
            RiskAssessment assessment =
                    service.scoredAssessment(TRANSACTION, rules(BigDecimal.ZERO), scored("78.5678"), ASSESSED_AT);

            // NUMERIC(5,2) would round this on the way in, and the event is
            // built from the entity in memory - so without this the row and the
            // event would disagree about the same number.
            assertThat(assessment.getModelScore()).isEqualByComparingTo("78.57");
            assertThat(assessment.getModelScore().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("the caller's latency is what the row records, not the service's own")
        void recordsTheCallerMeasuredLatency() {
            // The response says the model spent 3 ms on itself; the client
            // measured 41 ms across the network and any retries. The difference
            // is what tells an operator a slow link from a slow model.
            RiskAssessment assessment =
                    service.scoredAssessment(TRANSACTION, rules(BigDecimal.ZERO), scored("50.00"), ASSESSED_AT);

            assertThat(assessment.getScoringLatencyMs()).isEqualTo(41);
        }

        @Test
        @DisplayName("rules lead, then the model, each ordered within itself")
        void groupsReasonsBySourceRatherThanSortingAsOneList() {
            RuleOutcome outcome = rulesWith(
                    new BigDecimal("35.00"), reason(RuleCode.OFF_HOURS, "10"), reason(RuleCode.VELOCITY_5M_HIGH, "25"));

            RiskAssessment assessment = service.scoredAssessment(
                    TRANSACTION,
                    outcome,
                    scored(
                            "90.00",
                            new ReasonContribution("NEW_DEVICE", new BigDecimal("0.4000")),
                            new ReasonContribution("SPEND_24H_LOW", new BigDecimal("-1.2000"))),
                    ASSESSED_AT);

            // A rule weight of 10 and a log-odds contribution of 1.2 are not
            // comparable magnitudes. Interleaving them by size would put
            // SPEND_24H_LOW above OFF_HOURS on the strength of a comparison
            // that means nothing.
            assertThat(assessment.getReasonCodes())
                    .extracting(ReasonCode::code)
                    .containsExactly("VELOCITY_5M_HIGH", "OFF_HOURS", "SPEND_24H_LOW", "NEW_DEVICE");
            assertThat(assessment.getReasonCodes())
                    .extracting(ReasonCode::source)
                    .containsExactly(ReasonSource.RULE, ReasonSource.RULE, ReasonSource.MODEL, ReasonSource.MODEL);
        }

        @Test
        @DisplayName("a model reason carries a sentence saying what its number is not")
        void modelReasonsExplainTheirOwnScale() {
            RiskAssessment assessment = service.scoredAssessment(
                    TRANSACTION,
                    rules(BigDecimal.ZERO),
                    scored("90.00", new ReasonContribution("NEW_DEVICE", new BigDecimal("0.4213"))),
                    ASSESSED_AT);

            ReasonCode modelReason = assessment.getReasonCodes().get(0);
            assertThat(modelReason.contribution())
                    .as("the field carries what the model produced, unrounded")
                    .isEqualByComparingTo("0.4213");
            assertThat(modelReason.description())
                    .contains("+0.4213")
                    .contains("do not sum to the score")
                    .isNotEmpty();
            assertThat(modelReason.description().length())
                    .as("the contract caps a description at 500 characters")
                    .isLessThanOrEqualTo(500);
        }

        @Test
        @DisplayName("a negative model contribution is printed as one")
        void negativeContributionsKeepTheirSign() {
            RiskAssessment assessment = service.scoredAssessment(
                    TRANSACTION,
                    rules(BigDecimal.ZERO),
                    scored("90.00", new ReasonContribution("HISTORY_SIZE_LOW", new BigDecimal("-0.8100"))),
                    ASSESSED_AT);

            assertThat(assessment.getReasonCodes().get(0).description()).contains("-0.8100");
        }

        @Test
        @DisplayName("the reason list is capped, and it is the model's reasons that are dropped")
        void keepsEveryRuleReasonWhenTheCapBinds() {
            // Not reachable with today's seven rules and a scoring contract that
            // caps its own list at ten. It is asserted because if it ever does
            // bind, dropping a rule reason would make the rule arithmetic stop
            // adding up, and an analyst checking it would find a number they
            // could not reproduce.
            List<RuleReason> ruleReasons = new ArrayList<>();
            for (RuleCode code : RuleCode.values()) {
                ruleReasons.add(reason(code, "10"));
            }

            List<ReasonContribution> modelReasons = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                modelReasons.add(new ReasonContribution("MODEL_FEATURE_" + index, new BigDecimal("0.5")));
            }

            RiskAssessment assessment = service.scoredAssessment(
                    TRANSACTION,
                    new RuleOutcome(new BigDecimal("70.00"), ruleReasons, "1.0.0"),
                    scored("90.00", modelReasons.toArray(ReasonContribution[]::new)),
                    ASSESSED_AT);

            assertThat(assessment.getReasonCodes()).hasSize(RiskAssessmentService.MAX_REASON_CODES);
            assertThat(assessment.getReasonCodes())
                    .filteredOn(code -> code.source() == ReasonSource.RULE)
                    .hasSize(RuleCode.values().length);
        }
    }

    // ----------------------------------------------------------------------- //
    // The degraded shape
    // ----------------------------------------------------------------------- //

    @Nested
    @DisplayName("when scoring did not answer")
    class Degraded {

        @Test
        @DisplayName("the rule score stands unchanged, and every model field is absent")
        void isTheRuleScoreAndNothingElse() {
            RiskAssessment assessment = service.degradedAssessment(
                    TRANSACTION, rules(new BigDecimal("75.00"), RuleCode.BALANCE_DRAIN_HIGH), ASSESSED_AT);

            assertThat(assessment.isDegraded()).isTrue();
            assertThat(assessment.getFinalScore())
                    .as("never scaled up to stand in for a missing model")
                    .isEqualByComparingTo("75.00");
            assertThat(assessment.getRiskBand()).isEqualTo(RiskBand.HIGH);
            assertThat(assessment.getModelScore())
                    .as("a zero would be a claim about this transaction, and no such claim was made")
                    .isNull();
            assertThat(assessment.getModelVersion()).isNull();
            assertThat(assessment.getFeatureVersion()).isNull();
            assertThat(assessment.getScoringLatencyMs())
                    .as("no call happened, so there is no duration to report")
                    .isZero();
        }

        @Test
        @DisplayName("it still names the ruleset and the policy that produced it")
        void namesTheVersionsItDoesHave() {
            RiskAssessment assessment = service.degradedAssessment(TRANSACTION, rules(BigDecimal.ZERO), ASSESSED_AT);

            assertThat(assessment.getRulesetVersion())
                    .as("the rules are the only half a degraded assessment is made of, so this is "
                            + "the version it most needs")
                    .isEqualTo("1.0.0");
            assertThat(assessment.getPolicyVersion()).isEqualTo("1.0.0");
        }

        @Test
        @DisplayName("its reasons are the rules' only")
        void carriesOnlyRuleReasons() {
            RiskAssessment assessment = service.degradedAssessment(
                    TRANSACTION, rules(new BigDecimal("25.00"), RuleCode.VELOCITY_5M_HIGH), ASSESSED_AT);

            assertThat(assessment.getReasonCodes())
                    .extracting(ReasonCode::source)
                    .containsOnly(ReasonSource.RULE);
        }
    }

    // ----------------------------------------------------------------------- //
    // The quiet transaction, which is the ordinary one
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("a transaction that tripped nothing says so rather than saying nothing")
    void aCleanTransactionCarriesTheNoIndicatorsReason() {
        RiskAssessment assessment = service.degradedAssessment(TRANSACTION, rules(BigDecimal.ZERO), ASSESSED_AT);

        // The column requires at least one reason, and its comment says why: an
        // assessment with no reason at all cannot be defended to anyone. Most
        // transactions trip nothing, so something has to be said about them.
        assertThat(assessment.getReasonCodes()).containsExactly(ReasonCode.noIndicators());
        assertThat(assessment.getRiskBand()).isEqualTo(RiskBand.LOW);
    }

    @Test
    @DisplayName("a scored transaction that tripped nothing and attributed nothing says so too")
    void aCleanScoredTransactionCarriesItAsWell() {
        RiskAssessment assessment =
                service.scoredAssessment(TRANSACTION, rules(BigDecimal.ZERO), scored("0.00"), ASSESSED_AT);

        // A model that cannot be decomposed returns an empty reason list and a
        // warning saying why, so this is reachable through a real response
        // rather than only through a contrived one.
        assertThat(assessment.getReasonCodes()).containsExactly(ReasonCode.noIndicators());
    }

    @Test
    @DisplayName("nothing written here claims an alert was raised")
    void neverClaimsAnAlert() {
        // Alert creation is Phase 5. Writing true would be a claim with nothing
        // behind it, and a console counting alerts from this column would be
        // counting alerts that do not exist.
        assertThat(service.scoredAssessment(
                                TRANSACTION,
                                rules(new BigDecimal("95.00"), RuleCode.VELOCITY_5M_HIGH),
                                scored("99.00"),
                                ASSESSED_AT)
                        .isAlertRaised())
                .isFalse();
        assertThat(service.degradedAssessment(TRANSACTION, rules(new BigDecimal("95.00")), ASSESSED_AT)
                        .isAlertRaised())
                .isFalse();
    }

    // ----------------------------------------------------------------------- //
    // Fixtures
    // ----------------------------------------------------------------------- //

    private static RuleOutcome rules(BigDecimal score, RuleCode... fired) {
        List<RuleReason> reasons = new ArrayList<>();
        for (RuleCode code : fired) {
            reasons.add(reason(code, "25"));
        }
        return new RuleOutcome(score, reasons, "1.0.0");
    }

    private static RuleOutcome rulesWith(BigDecimal score, RuleReason... fired) {
        return new RuleOutcome(score, List.of(fired), "1.0.0");
    }

    private static RuleReason reason(RuleCode code, String contribution) {
        return new RuleReason(code, "a synthetic indicator fired", new BigDecimal(contribution));
    }

    private static ScoringResult scored(String modelScore, ReasonContribution... reasons) {
        return new ScoringResult(
                new ScoreResponse(
                        "2.1.0",
                        "1.0.0",
                        new BigDecimal(modelScore),
                        List.of(reasons),
                        new BigDecimal("3.2"),
                        List.of()),
                41);
    }

    private static RiskPolicyProperties policy() {
        Map<RiskBand, BigDecimal> bounds = new EnumMap<>(RiskBand.class);
        bounds.put(RiskBand.LOW, new BigDecimal("0"));
        bounds.put(RiskBand.MEDIUM, new BigDecimal("40"));
        bounds.put(RiskBand.HIGH, new BigDecimal("70"));
        bounds.put(RiskBand.CRITICAL, new BigDecimal("90"));
        return new RiskPolicyProperties("1.0.0", new BigDecimal("0.6"), bounds);
    }
}
