/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.risk.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.github.la3679.sentinelflow.api.domain.ReasonSource;
import io.github.la3679.sentinelflow.api.domain.TransactionChannel;
import io.github.la3679.sentinelflow.api.domain.TransactionType;
import io.github.la3679.sentinelflow.api.scoring.payload.AccountContext;
import io.github.la3679.sentinelflow.api.scoring.payload.Amount;
import io.github.la3679.sentinelflow.api.scoring.payload.RecentTransaction;
import io.github.la3679.sentinelflow.api.scoring.payload.TransactionToScore;

/**
 * The ruleset, indicator by indicator.
 *
 * <p>Each indicator gets a firing case and a not-firing case, because a rule that fires on
 * everything and a rule that fires on nothing both produce a suite that passes. The pairs are what
 * assert anything.
 *
 * <p>A fixed instant throughout: 12:00 UTC on a day nothing else depends on, so the off-hours
 * indicator is quiet unless a test deliberately moves the transaction into the small hours. Every
 * value here is synthetic and illustrative.
 */
class RuleEngineTests {

    private static final Instant NOON = Instant.parse("2026-08-26T12:00:00Z");
    private static final Instant SMALL_HOURS = Instant.parse("2026-08-26T03:10:00Z");

    private static final String KNOWN_DEVICE = "DEV-0123456789ab";
    private static final String OTHER_DEVICE = "DEV-fedcba987654";

    private final RuleEngine engine = new RuleEngine(properties());

    // ----------------------------------------------------------------------- //
    // The quiet case
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("an ordinary transaction trips nothing and scores zero")
    void ordinaryTransactionIsClean() {
        RuleOutcome outcome = engine.evaluate(transaction(), context(history(1, "100.00")));

        assertThat(outcome.isClean()).isTrue();
        assertThat(outcome.score()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(outcome.rulesetVersion()).isEqualTo("1.0.0");
    }

    @Test
    @DisplayName("an account with no history at all is not treated as suspicious")
    void noHistoryIsNotSuspicious() {
        RuleOutcome outcome = engine.evaluate(transaction(), context(List.of()));

        // The ratio and country indicators have nothing to compare against, and a
        // default that read as extreme would make every account's first
        // transaction its most alarming.
        assertThat(outcome.isClean())
                .as("a new account is not a suspicious account")
                .isTrue();
    }

    // ----------------------------------------------------------------------- //
    // Velocity
    // ----------------------------------------------------------------------- //

    @Nested
    @DisplayName("velocity over five minutes")
    class Velocity {

        @Test
        @DisplayName("fires at the configured count, which is a floor rather than a strict excess")
        void firesAtTheThreshold() {
            List<RecentTransaction> recent = new ArrayList<>();
            for (int index = 1; index <= 4; index++) {
                recent.add(recent(NOON.minusSeconds(index * 30L), "100.00", "MER-0001", KNOWN_DEVICE, "GB"));
            }

            assertThat(codes(engine.evaluate(transaction(), context(recent)))).contains(RuleCode.VELOCITY_5M_HIGH);
        }

        @Test
        @DisplayName("does not fire one short of it")
        void quietBelowTheThreshold() {
            List<RecentTransaction> recent = new ArrayList<>();
            for (int index = 1; index <= 3; index++) {
                recent.add(recent(NOON.minusSeconds(index * 30L), "100.00", "MER-0001", KNOWN_DEVICE, "GB"));
            }

            assertThat(codes(engine.evaluate(transaction(), context(recent))))
                    .doesNotContain(RuleCode.VELOCITY_5M_HIGH);
        }

        @Test
        @DisplayName("counts the window, not the context")
        void countsOnlyInsideTheWindow() {
            // Four transactions, but spread across the day rather than the five
            // minutes the indicator is defined over. A rule that counted the whole
            // context would fire on any moderately active account.
            List<RecentTransaction> recent = List.of(
                    recent(NOON.minusSeconds(30), "100.00", "MER-0001", KNOWN_DEVICE, "GB"),
                    recent(NOON.minus(2, ChronoUnit.HOURS), "100.00", "MER-0001", KNOWN_DEVICE, "GB"),
                    recent(NOON.minus(5, ChronoUnit.HOURS), "100.00", "MER-0001", KNOWN_DEVICE, "GB"),
                    recent(NOON.minus(9, ChronoUnit.HOURS), "100.00", "MER-0001", KNOWN_DEVICE, "GB"));

            assertThat(codes(engine.evaluate(transaction(), context(recent))))
                    .doesNotContain(RuleCode.VELOCITY_5M_HIGH);
        }
    }

    // ----------------------------------------------------------------------- //
    // Amount
    // ----------------------------------------------------------------------- //

    @Nested
    @DisplayName("amount against this account's own baseline")
    class AmountRatio {

        @Test
        @DisplayName("fires when the amount is several times the account's recent mean")
        void firesOnASpike() {
            RuleOutcome outcome = engine.evaluate(
                    transaction("1000.00", NOON, KNOWN_DEVICE, "GB", TransactionChannel.CARD_NOT_PRESENT),
                    context(history(3, "50.00")));

            assertThat(codes(outcome)).contains(RuleCode.AMOUNT_RATIO_HIGH);
        }

        @Test
        @DisplayName("a large amount on an account that always spends large is not a spike")
        void relativeToTheAccountRatherThanToAnAbsoluteFigure() {
            RuleOutcome outcome = engine.evaluate(
                    transaction("1000.00", NOON, KNOWN_DEVICE, "GB", TransactionChannel.CARD_NOT_PRESENT),
                    context(history(3, "900.00")));

            assertThat(codes(outcome))
                    .as("an absolute threshold would fire on every wealthy customer and never on a "
                            + "drained account")
                    .doesNotContain(RuleCode.AMOUNT_RATIO_HIGH);
        }
    }

    // ----------------------------------------------------------------------- //
    // Device, country, hour, balance, merchants
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("a device the account has not used fires")
    void newDeviceFires() {
        List<RecentTransaction> recent =
                List.of(recent(NOON.minusSeconds(600), "100.00", "MER-0001", OTHER_DEVICE, "GB"));

        assertThat(codes(engine.evaluate(transaction(), context(recent)))).contains(RuleCode.NEW_DEVICE);
    }

    @Test
    @DisplayName("a device is not new when the account has no known devices at all")
    void newDeviceNeedsSomethingToCompareAgainst() {
        RuleOutcome outcome = engine.evaluate(transaction(), context(List.of()));

        assertThat(codes(outcome))
                .as("with no history, \"not one of the account's devices\" is vacuous rather than "
                        + "true, and firing would put fifteen points on the first transaction of "
                        + "every account that has been quiet for a day")
                .doesNotContain(RuleCode.NEW_DEVICE);
    }

    @Test
    @DisplayName("a cash withdrawal is not a new device, because an ATM has none")
    void deviceLessChannelsDoNotFire() {
        RuleOutcome outcome = engine.evaluate(
                transaction("100.00", NOON, null, "GB", TransactionChannel.ATM),
                context(List.of(recent(NOON.minusSeconds(600), "100.00", "MER-0001", OTHER_DEVICE, "GB"))));

        assertThat(codes(outcome))
                .as("firing here would put NEW_DEVICE on every cash withdrawal an account ever made")
                .doesNotContain(RuleCode.NEW_DEVICE);
    }

    @Test
    @DisplayName("a different country from the most recent transaction fires")
    void countryChangeFires() {
        List<RecentTransaction> recent =
                List.of(recent(NOON.minusSeconds(600), "100.00", "MER-0001", KNOWN_DEVICE, "FR"));

        assertThat(codes(engine.evaluate(transaction(), context(recent)))).contains(RuleCode.COUNTRY_CHANGE);
    }

    @Test
    @DisplayName("the small hours fire, and midday does not")
    void offHoursFires() {
        RuleOutcome small = engine.evaluate(
                transaction("100.00", SMALL_HOURS, KNOWN_DEVICE, "GB", TransactionChannel.CARD_NOT_PRESENT),
                context(historyBefore(SMALL_HOURS, 1, "100.00")));

        assertThat(codes(small)).contains(RuleCode.OFF_HOURS);
        assertThat(codes(engine.evaluate(transaction(), context(history(1, "100.00")))))
                .doesNotContain(RuleCode.OFF_HOURS);
    }

    @Test
    @DisplayName("moving half the balance fires")
    void balanceDrainFires() {
        RuleOutcome outcome = engine.evaluate(
                transaction("600.00", NOON, KNOWN_DEVICE, "GB", TransactionChannel.CARD_NOT_PRESENT),
                context(history(1, "600.00"), "1000.00"));

        assertThat(codes(outcome)).contains(RuleCode.BALANCE_DRAIN_HIGH);
    }

    @Test
    @DisplayName("a non-positive balance does not fire, because the ratio is undefined rather than large")
    void nonPositiveBalanceDoesNotFire() {
        RuleOutcome outcome = engine.evaluate(
                transaction("600.00", NOON, KNOWN_DEVICE, "GB", TransactionChannel.CARD_NOT_PRESENT),
                context(history(1, "600.00"), "0.00"));

        assertThat(codes(outcome))
                .as("an overdrawn account would otherwise be permanently alarming")
                .doesNotContain(RuleCode.BALANCE_DRAIN_HIGH);
    }

    @Test
    @DisplayName("four distinct merchants within the hour fire; four visits to one merchant do not")
    void distinctMerchantsFires() {
        List<RecentTransaction> spread = List.of(
                recent(NOON.minusSeconds(600), "10.00", "MER-0001", KNOWN_DEVICE, "GB"),
                recent(NOON.minusSeconds(1200), "10.00", "MER-0002", KNOWN_DEVICE, "GB"),
                recent(NOON.minusSeconds(1800), "10.00", "MER-0003", KNOWN_DEVICE, "GB"),
                recent(NOON.minusSeconds(2400), "10.00", "MER-0004", KNOWN_DEVICE, "GB"));
        List<RecentTransaction> loyal = List.of(
                recent(NOON.minusSeconds(600), "10.00", "MER-0001", KNOWN_DEVICE, "GB"),
                recent(NOON.minusSeconds(1200), "10.00", "MER-0001", KNOWN_DEVICE, "GB"),
                recent(NOON.minusSeconds(1800), "10.00", "MER-0001", KNOWN_DEVICE, "GB"),
                recent(NOON.minusSeconds(2400), "10.00", "MER-0001", KNOWN_DEVICE, "GB"));

        assertThat(codes(engine.evaluate(transaction(), context(spread))))
                .contains(RuleCode.DISTINCT_MERCHANTS_1H_HIGH);
        assertThat(codes(engine.evaluate(transaction(), context(loyal))))
                .as("distinct merchants, not transactions — otherwise this duplicates velocity")
                .doesNotContain(RuleCode.DISTINCT_MERCHANTS_1H_HIGH);
    }

    // ----------------------------------------------------------------------- //
    // The properties of the outcome itself
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("the score is the sum of the weights that fired, and an analyst can add it up")
    void scoreIsTheSumOfItsReasons() {
        // A new device in a new country, in the small hours.
        RuleOutcome outcome = engine.evaluate(
                transaction("100.00", SMALL_HOURS, KNOWN_DEVICE, "GB", TransactionChannel.CARD_NOT_PRESENT),
                context(List.of(recent(SMALL_HOURS.minusSeconds(600), "100.00", "MER-0001", OTHER_DEVICE, "FR"))));

        assertThat(codes(outcome))
                .containsExactlyInAnyOrder(RuleCode.NEW_DEVICE, RuleCode.COUNTRY_CHANGE, RuleCode.OFF_HOURS);
        assertThat(outcome.score())
                .as("15 + 15 + 10, which is the property a ruleset has and a model does not")
                .isEqualByComparingTo(new BigDecimal("40.00"));
    }

    @Test
    @DisplayName("reasons come back most significant first, deterministically")
    void reasonsAreOrdered() {
        RuleOutcome outcome = engine.evaluate(
                transaction("100.00", SMALL_HOURS, KNOWN_DEVICE, "GB", TransactionChannel.CARD_NOT_PRESENT),
                context(List.of(recent(SMALL_HOURS.minusSeconds(600), "100.00", "MER-0001", OTHER_DEVICE, "FR"))));

        List<BigDecimal> contributions =
                outcome.reasons().stream().map(RuleReason::contribution).toList();

        assertThat(contributions).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        // COUNTRY_CHANGE and NEW_DEVICE both weigh 15; the code breaks the tie, so
        // the same request cannot produce two different persisted orders.
        assertThat(outcome.reasons().get(0).code()).isEqualTo(RuleCode.COUNTRY_CHANGE);
        assertThat(outcome.reasons().get(1).code()).isEqualTo(RuleCode.NEW_DEVICE);
    }

    @Test
    @DisplayName("every reason is sourced to the rules, never to a model")
    void everyReasonIsARuleReason() {
        RuleOutcome outcome = engine.evaluate(
                transaction("100.00", SMALL_HOURS, KNOWN_DEVICE, "GB", TransactionChannel.CARD_NOT_PRESENT),
                context(history(1, "100.00")));

        assertThat(outcome.reasons())
                .allSatisfy(reason -> assertThat(reason.source()).isEqualTo(ReasonSource.RULE));
    }

    @Test
    @DisplayName("the score is clipped to the contract's scale when everything fires")
    void scoreIsClipped() {
        // Every indicator at once: a huge amount on a new device in a new country
        // in the small hours, after a burst across four merchants.
        List<RecentTransaction> recent = List.of(
                recent(SMALL_HOURS.minusSeconds(30), "10.00", "MER-0001", OTHER_DEVICE, "FR"),
                recent(SMALL_HOURS.minusSeconds(60), "10.00", "MER-0002", OTHER_DEVICE, "FR"),
                recent(SMALL_HOURS.minusSeconds(90), "10.00", "MER-0003", OTHER_DEVICE, "FR"),
                recent(SMALL_HOURS.minusSeconds(120), "10.00", "MER-0004", OTHER_DEVICE, "FR"));

        RuleOutcome outcome = engine.evaluate(
                transaction("900.00", SMALL_HOURS, KNOWN_DEVICE, "GB", TransactionChannel.CARD_NOT_PRESENT),
                context(recent, "1000.00"));

        assertThat(outcome.reasons()).hasSize(RuleCode.values().length);
        assertThat(outcome.score())
                .as("the weights sum to 110 on purpose, so this is a real clip")
                .isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("the same request scores the same twice")
    void deterministic() {
        RuleOutcome first = engine.evaluate(transaction(), context(history(2, "100.00")));
        RuleOutcome second = engine.evaluate(transaction(), context(history(2, "100.00")));

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("history at or after the scored transaction is excluded")
    void neverLooksForward() {
        // A replayed transaction legitimately carries context from after itself,
        // because the assembler windows on when it asked. An indicator computed
        // over a transaction's own future looks excellent and means nothing.
        List<RecentTransaction> future = new ArrayList<>();
        for (int index = 1; index <= 4; index++) {
            future.add(recent(NOON.plusSeconds(index * 30L), "100.00", "MER-000" + index, OTHER_DEVICE, "FR"));
        }
        future.add(recent(NOON, "100.00", "MER-0009", OTHER_DEVICE, "FR"));

        RuleOutcome outcome = engine.evaluate(transaction(), context(future));

        assertThat(outcome.isClean())
                .as("every one of those is at or after the scored transaction")
                .isTrue();
    }

    // ----------------------------------------------------------------------- //
    // Fixtures
    // ----------------------------------------------------------------------- //

    private static RiskRulesProperties properties() {
        return new RiskRulesProperties(
                "1.0.0",
                4,
                new BigDecimal("5.0"),
                new BigDecimal("0.5"),
                4,
                java.util.Map.of(
                        RuleCode.VELOCITY_5M_HIGH, new BigDecimal("25"),
                        RuleCode.AMOUNT_RATIO_HIGH, new BigDecimal("20"),
                        RuleCode.NEW_DEVICE, new BigDecimal("15"),
                        RuleCode.COUNTRY_CHANGE, new BigDecimal("15"),
                        RuleCode.BALANCE_DRAIN_HIGH, new BigDecimal("15"),
                        RuleCode.OFF_HOURS, new BigDecimal("10"),
                        RuleCode.DISTINCT_MERCHANTS_1H_HIGH, new BigDecimal("10")));
    }

    private static TransactionToScore transaction() {
        return transaction("100.00", NOON, KNOWN_DEVICE, "GB", TransactionChannel.CARD_NOT_PRESENT);
    }

    private static TransactionToScore transaction(
            String amount, Instant at, String device, String country, TransactionChannel channel) {
        return new TransactionToScore(
                UUID.fromString("0198f0a1-2b3c-7d4e-8f90-1a2b3c4d5e6f"),
                "ACC-000123",
                "MER-0042",
                "5411",
                TransactionType.PURCHASE,
                channel,
                new Amount(amount, "GBP"),
                country,
                device,
                at);
    }

    private static AccountContext context(List<RecentTransaction> recent) {
        return context(recent, "5000.00");
    }

    private static AccountContext context(List<RecentTransaction> recent, String balance) {
        return new AccountContext(
                1, 86_400, NOON.minus(400, ChronoUnit.DAYS), new Amount(balance, "GBP"), recent, false);
    }

    /** {@code count} ordinary transactions, well outside every window, at a steady amount. */
    private static List<RecentTransaction> history(int count, String amount) {
        return historyBefore(NOON, count, amount);
    }

    private static List<RecentTransaction> historyBefore(Instant at, int count, String amount) {
        List<RecentTransaction> recent = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            recent.add(recent(at.minus(index * 2L, ChronoUnit.HOURS), amount, "MER-000" + index, KNOWN_DEVICE, "GB"));
        }
        return recent;
    }

    private static RecentTransaction recent(Instant at, String amount, String merchant, String device, String country) {
        return new RecentTransaction(
                at,
                new Amount(amount, "GBP"),
                merchant,
                device,
                country,
                TransactionChannel.CARD_NOT_PRESENT,
                TransactionType.PURCHASE);
    }

    private static List<RuleCode> codes(RuleOutcome outcome) {
        return outcome.reasons().stream().map(RuleReason::code).toList();
    }
}
