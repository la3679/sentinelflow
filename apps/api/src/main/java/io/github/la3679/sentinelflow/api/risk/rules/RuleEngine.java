/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.risk.rules;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import io.github.la3679.sentinelflow.api.domain.TransactionChannel;
import io.github.la3679.sentinelflow.api.scoring.payload.AccountContext;
import io.github.la3679.sentinelflow.api.scoring.payload.RecentTransaction;
import io.github.la3679.sentinelflow.api.scoring.payload.ScoreRequest;
import io.github.la3679.sentinelflow.api.scoring.payload.TransactionToScore;

/**
 * The transparent rule indicators, evaluated in-process against a transaction and its account
 * context.
 *
 * <h2>Why this is here and not in {@code apps/scoring}</h2>
 *
 * ADR-0002 §3 assigns deterministic rule scoring to this service, and ADR-0008 §3 is the reason: when
 * the scoring service is unreachable the assessment degrades to rules alone. A ruleset that had to be
 * reached over the network to answer "the network is down" would answer nothing. So this runs in the
 * same process, in the same transaction, with no dependency that can fail.
 *
 * <h2>What it is, and what it is not</h2>
 *
 * <p><strong>It is a floor.</strong> A handful of additive indicators a fraud analyst would
 * recognise, each contributing a configured weight, summed and clipped. There is no fitting, no
 * threshold learned from data, and no attempt at calibration — a "baseline" tuned on the training set
 * is not a baseline, it is another model with fewer parameters, and a model that only beats one of
 * those has not been shown to be worth serving.
 *
 * <p><strong>These are illustrative synthetic indicators, not any institution's fraud rules.</strong>
 * They exist to be readable and to be beaten, and they are written against the shapes this project's
 * own generator plants.
 *
 * <p><strong>It never decides whether an alert exists.</strong> It returns a score and the reasons
 * for it. Banding and alerting are versioned policy, on their own schedule (ADR-0008 §4).
 *
 * <h2>Determinism, and why the window is re-applied here</h2>
 *
 * Pure over its two arguments. No clock, no randomness, no state between calls — the same request
 * scores the same on any machine on any day, which is what makes a rule score reproducible in a way
 * a model score can only be attributed.
 *
 * <p>The history is filtered to strictly before the scored transaction's own {@code occurredAt},
 * even though {@link io.github.la3679.sentinelflow.api.scoring.AccountContextAssembler} already
 * windows it that way. Not defensive duplication: a context can legitimately arrive from elsewhere —
 * the labelled export, a replay, a test — and an indicator computed over a transaction's own future
 * is the same defect here as it is in the feature pipeline, with the same symptom of looking
 * excellent and meaning nothing.
 */
@Service
public class RuleEngine {

    /**
     * Hours treated as "off", in UTC.
     *
     * <p>Deliberately narrow, and deliberately the same window {@code features/extraction.py} uses:
     * 02:00 to 04:59 is unusual for retail activity in a way 23:00 is not, and two definitions of
     * "off hours" in one system would be two answers to the same question.
     *
     * <p>UTC because that is what the schema stores and the contract carries. A local hour would need
     * a timezone per account, which the schema does not record, and inventing one would make the
     * indicator depend on a guess.
     */
    private static final Set<Integer> OFF_HOURS = Set.of(2, 3, 4);

    /** Five minutes, in seconds. Named because {@code 300} in a window check reads as nothing. */
    private static final long VELOCITY_WINDOW_SECONDS = 300;

    /** One hour, for the distinct-merchant count. */
    private static final long MERCHANT_WINDOW_SECONDS = 3600;

    /**
     * Channels with no device by nature.
     *
     * <p>A null device on one of these is a real answer rather than a missing one. Treating it as a
     * new device would fire {@code NEW_DEVICE} on every cash withdrawal an account ever made, which
     * is the kind of rule that trains analysts to ignore a code.
     */
    private static final Set<TransactionChannel> DEVICELESS_CHANNELS =
            EnumSet.of(TransactionChannel.ATM, TransactionChannel.DIRECT_DEBIT);

    /** Enough precision for a ratio of two money values; the result is compared, never stored. */
    private static final MathContext RATIO = new MathContext(12, RoundingMode.HALF_UP);

    private final RiskRulesProperties rules;

    public RuleEngine(RiskRulesProperties rules) {
        this.rules = rules;
    }

    /** Convenience for the ordinary caller, which has an assembled request in hand. */
    public RuleOutcome evaluate(ScoreRequest request) {
        return evaluate(request.transaction(), request.accountContext());
    }

    /**
     * Score one transaction against the configured ruleset.
     *
     * @return the clipped score, the rules that fired ordered by descending contribution, and the
     *     ruleset version that produced both
     */
    public RuleOutcome evaluate(TransactionToScore transaction, AccountContext context) {
        List<RecentTransaction> history = historyBefore(context, transaction.occurredAt());
        List<RuleReason> reasons = new ArrayList<>();

        int within5m = countWithin(history, transaction.occurredAt(), VELOCITY_WINDOW_SECONDS);
        if (within5m >= rules.velocity5mCount()) {
            reasons.add(reason(
                    RuleCode.VELOCITY_5M_HIGH,
                    "%d transactions on this account in the five minutes before it, at or above the threshold of %d"
                            .formatted(within5m, rules.velocity5mCount())));
        }

        BigDecimal amount = amountOf(transaction);
        BigDecimal ratio = amountRatio(amount, history);
        if (ratio != null && ratio.compareTo(rules.amountRatio()) >= 0) {
            reasons.add(reason(
                    RuleCode.AMOUNT_RATIO_HIGH,
                    "the amount is %s times this account's own recent mean, at or above the threshold of %s"
                            .formatted(ratio.setScale(1, RoundingMode.HALF_UP).toPlainString(), rules.amountRatio())));
        }

        if (isNewDevice(transaction, history)) {
            reasons.add(reason(
                    RuleCode.NEW_DEVICE,
                    "device %s has not been used by this account inside the lookback window"
                            .formatted(transaction.deviceReference())));
        }

        if (isCountryChange(transaction, history)) {
            reasons.add(reason(
                    RuleCode.COUNTRY_CHANGE,
                    "originates in %s where the previous transaction originated in %s"
                            .formatted(
                                    transaction.originCountry(), history.get(0).originCountry())));
        }

        int hour = transaction.occurredAt().atZone(ZoneOffset.UTC).getHour();
        if (OFF_HOURS.contains(hour)) {
            reasons.add(reason(RuleCode.OFF_HOURS, "occurred at %02d:00 UTC, inside the small hours".formatted(hour)));
        }

        BigDecimal drain = balanceDrainRatio(amount, context);
        if (drain != null && drain.compareTo(rules.balanceDrainRatio()) >= 0) {
            reasons.add(reason(
                    RuleCode.BALANCE_DRAIN_HIGH,
                    "moves %s of the account balance, at or above the threshold of %s"
                            .formatted(
                                    drain.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                                    rules.balanceDrainRatio())));
        }

        int merchants = distinctMerchantsWithin(history, transaction.occurredAt(), MERCHANT_WINDOW_SECONDS);
        if (merchants >= rules.distinctMerchants1hCount()) {
            reasons.add(reason(
                    RuleCode.DISTINCT_MERCHANTS_1H_HIGH,
                    "%d distinct merchants on this account within the hour before it, at or above the threshold of %d"
                            .formatted(merchants, rules.distinctMerchants1hCount())));
        }

        // Descending contribution, then by code, so two evaluations of the same
        // request produce the same list. A reason order that moved between runs
        // would make a persisted assessment unreproducible for no reason at all.
        reasons.sort(Comparator.comparing(RuleReason::contribution).reversed().thenComparing(reason -> reason.code()
                .name()));

        return new RuleOutcome(clip(total(reasons)), reasons, rules.version());
    }

    private RuleReason reason(RuleCode code, String description) {
        return new RuleReason(code, description, rules.weightOf(code));
    }

    /**
     * History strictly before the scored transaction.
     *
     * <p>Strictly, not at-or-before: a transaction at the same instant cannot be known to have
     * preceded this one, and including it would make an indicator depend on tie-breaking.
     */
    private static List<RecentTransaction> historyBefore(AccountContext context, Instant at) {
        return context.recentTransactions().stream()
                .filter(item -> item.occurredAt().isBefore(at))
                .sorted(Comparator.comparing(RecentTransaction::occurredAt).reversed())
                .toList();
    }

    private static int countWithin(List<RecentTransaction> history, Instant at, long seconds) {
        Instant floor = at.minusSeconds(seconds);
        return (int) history.stream()
                .filter(item -> item.occurredAt().isAfter(floor))
                .count();
    }

    private static int distinctMerchantsWithin(List<RecentTransaction> history, Instant at, long seconds) {
        Instant floor = at.minusSeconds(seconds);
        Set<String> merchants = new HashSet<>();
        for (RecentTransaction item : history) {
            if (item.occurredAt().isAfter(floor)) {
                merchants.add(item.merchantReference());
            }
        }
        return merchants.size();
    }

    /**
     * This amount against what this account usually spends, or null when there is nothing to compare
     * against.
     *
     * <p>Null rather than a large number for an account with no history. A default that read as an
     * extreme ratio would make every account's first transaction its most alarming, which is both
     * wrong and the sort of wrong that produces a queue of new customers.
     */
    private static BigDecimal amountRatio(BigDecimal amount, List<RecentTransaction> history) {
        if (history.isEmpty()) {
            return null;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (RecentTransaction item : history) {
            total = total.add(new BigDecimal(item.amount().value()).abs());
        }
        BigDecimal mean = total.divide(BigDecimal.valueOf(history.size()), RATIO);
        if (mean.signum() == 0) {
            return null;
        }
        return amount.abs().divide(mean, RATIO);
    }

    /**
     * Whether this device is one the account has not used, rather than one it cannot vouch for.
     *
     * <p><strong>An empty history does not fire.</strong> With no prior transactions the account has
     * no known devices, so "not one of them" is vacuous rather than true — and firing would put
     * fifteen points on the first transaction of every account that has been quiet for a day, which
     * is most low-activity accounts on most days. Found by the test asserting that an account with no
     * history is not suspicious, and it is the same principle that leaves the amount ratio and the
     * country change without a default.
     *
     * <p><strong>This deliberately differs from the model feature of the same name.</strong>
     * {@code features/extraction.py} reports {@code is_new_device} as 1.0 on an empty history, and
     * that is right there: the model sees {@code history_size} beside it and learns what the pair
     * means together. A rule asserts a fixed weight with nothing beside it, so it has to carry the
     * qualification itself.
     */
    private static boolean isNewDevice(TransactionToScore transaction, List<RecentTransaction> history) {
        if (history.isEmpty()
                || DEVICELESS_CHANNELS.contains(transaction.channel())
                || transaction.deviceReference() == null) {
            return false;
        }
        return history.stream().noneMatch(item -> transaction.deviceReference().equals(item.deviceReference()));
    }

    private static boolean isCountryChange(TransactionToScore transaction, List<RecentTransaction> history) {
        return !history.isEmpty()
                && !transaction.originCountry().equals(history.get(0).originCountry());
    }

    /**
     * How much of the balance this transaction moves, or null when the balance is not positive.
     *
     * <p>A ratio against a non-positive denominator is not a large number, it is an undefined one,
     * and firing on it would make an overdrawn account permanently alarming.
     */
    private static BigDecimal balanceDrainRatio(BigDecimal amount, AccountContext context) {
        BigDecimal balance = new BigDecimal(context.currentBalance().value());
        if (balance.signum() <= 0) {
            return null;
        }
        return amount.abs().divide(balance, RATIO);
    }

    private static BigDecimal amountOf(TransactionToScore transaction) {
        return new BigDecimal(transaction.amount().value());
    }

    private static BigDecimal total(List<RuleReason> reasons) {
        return reasons.stream().map(RuleReason::contribution).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * The contract's scale, applied rather than assumed.
     *
     * <p>The weights are allowed to sum above 100 (see {@link RiskRulesProperties#totalWeight()}), so
     * this is a real clip and not a formality. Scale 2, matching {@code NUMERIC(5,2)} on
     * {@code risk_assessments.rule_score}: a value the column would round is a value the database and
     * the response would disagree about.
     */
    private static BigDecimal clip(BigDecimal score) {
        return score.min(RiskRulesProperties.SCORE_MAX)
                .max(RiskRulesProperties.SCORE_MIN)
                .setScale(2, RoundingMode.HALF_UP);
    }
}
