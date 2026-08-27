/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.seed.scenario;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import io.github.la3679.sentinelflow.api.domain.TransactionChannel;
import io.github.la3679.sentinelflow.api.domain.TransactionType;
import io.github.la3679.sentinelflow.api.seed.SeedProfile;
import io.github.la3679.sentinelflow.api.web.dto.AmountRequest;
import io.github.la3679.sentinelflow.api.web.dto.TransactionRequest;

/**
 * Produces a stream of synthetic transactions: ordinary traffic with detectable shapes planted in
 * it.
 *
 * <p><strong>Pure, and deterministic.</strong> No database, no clock, no identifiers. Given the same
 * seed, profile, window start, accounts and merchants it returns the same list on any machine and
 * any JDK — {@link Random} is specified exactly by the JDK rather than left to the implementation,
 * which is what makes "reproduce it with seed 20260826" an instruction rather than a hope.
 *
 * <p><strong>The window start is a parameter, not a clock read.</strong> A demo wants traffic that
 * ends near now, so absolute times cannot be fixed constants; but a class that read the clock itself
 * could not be asserted against, and two runs would differ in a way no checksum could see past. So
 * the caller supplies the instant and every transaction here is placed at a {@link Duration} offset
 * from it. {@link ScenarioManifest}'s checksum covers the offsets rather than the instants for the
 * same reason: a reproduction claim is about the shape of the traffic, not about the day it ran.
 *
 * <p><strong>The background matters as much as the shapes.</strong> Traffic where every account
 * behaves identically makes a velocity feature trivially predictive and an evaluation meaningless.
 * So an account's ordinary spending is drawn around a baseline of its own, it favours a handful of
 * merchants it has used before, and it uses one of two devices — which is what makes "a merchant
 * this account has never used" and "a device this account has never used" mean something when a
 * planted shape breaks the habit.
 *
 * <p><strong>Nothing here is a label in the database.</strong> {@link GeneratedTransaction} carries
 * the shape it was planted as; the loader writes the transaction and drops the label. See
 * {@link ScenarioType}.
 */
public final class ScenarioGenerator {

    /** Bump when a change here would produce different traffic from the same seed. */
    public static final String GENERATOR_VERSION = "1.1.0";

    /** How far back the generated window reaches. Long enough for a daily rhythm to be visible. */
    private static final Duration WINDOW = Duration.ofDays(14);

    /**
     * Home countries, weighted by position: most accounts are the first, which is what makes a
     * country change worth noticing. Invented distribution, not a claim about anywhere.
     */
    private static final List<String> HOME_COUNTRIES = List.of("GB", "GB", "GB", "IE", "FR", "DE", "ES");

    /** Somewhere a card-present purchase cannot follow a British one twenty minutes later. */
    private static final List<String> DISTANT_COUNTRIES = List.of("SG", "BR", "ZA", "JP", "AU");

    private static final List<TransactionChannel> ORDINARY_CHANNELS = List.of(
            TransactionChannel.CARD_PRESENT,
            TransactionChannel.CARD_NOT_PRESENT,
            TransactionChannel.CARD_NOT_PRESENT,
            TransactionChannel.ONLINE_TRANSFER);

    private static final String CURRENCY = "GBP";

    private final long seed;
    private final SeedProfile profile;

    /**
     * Makes every idempotency key unique without making one unpredictable.
     *
     * <p>Reset at the start of each {@link #generate} call, which is what keeps two calls on one
     * instance identical. <strong>Not thread-safe</strong>, and deliberately not: a generator that
     * could be driven concurrently would produce a different dataset depending on scheduling, which
     * is the one property this class exists to rule out.
     */
    private int sequence;

    /** Set for the duration of one {@link #generate} call. See {@link #sequence} on thread safety. */
    private Instant windowStart;

    public ScenarioGenerator(long seed, SeedProfile profile) {
        this.seed = seed;
        this.profile = profile;
    }

    /**
     * Generates the whole dataset, ordered by when it occurred.
     *
     * <p>Ordered because the loader writes it in order and because per-account ordering is the
     * guarantee the whole pipeline is built around (ADR-0006 §2). Generating out of order and
     * sorting afterwards — rather than generating in order — keeps each shape's construction local
     * and readable instead of interleaved by hand.
     *
     * @param windowStart when the generated window begins. Supplied rather than read from a clock:
     *     a demo wants traffic that ends near now, and a generator that read the clock itself could
     *     not be asserted against. Every offset is measured from here.
     *     <p><strong>Truncated to a UTC day boundary</strong>, so traffic may begin up to 24 hours
     *     earlier than asked. That is deliberate and load-bearing rather than a rounding
     *     convenience — see {@link #anchor(Instant)}.
     * @throws IllegalArgumentException if there is nothing to generate against, which is a caller
     *     error worth failing loudly on: silently producing an empty dataset would look like the
     *     generator working
     */
    public List<GeneratedTransaction> generate(
            Instant windowStart, List<GeneratorAccount> accounts, List<String> merchants) {
        if (accounts.isEmpty() || merchants.isEmpty()) {
            throw new IllegalArgumentException("Cannot generate transactions without accounts and merchants");
        }

        this.windowStart = anchor(windowStart);
        this.sequence = 0;
        Random random = new Random(seed);
        List<GeneratedTransaction> generated = new ArrayList<>();

        for (int i = 0; i < profile.transactions(); i++) {
            GeneratorAccount account = pick(random, accounts);
            generated.add(ordinary(random, account, merchants));
        }

        for (int i = 0; i < profile.scenarios(); i++) {
            GeneratorAccount account = pick(random, accounts);
            // Cycles rather than draws, so a small profile still gets one of
            // each shape instead of whichever the seed happened to favour. A CI
            // dataset missing a shape entirely would silently stop testing it.
            ScenarioType shape = PLANTED_SHAPES.get(i % PLANTED_SHAPES.size());
            generated.addAll(plant(random, shape, account, merchants));
        }

        // A stable tiebreak on the idempotency key, so equal offsets cannot
        // reorder between runs and make an otherwise deterministic dataset
        // produce two different checksums.
        generated.sort(Comparator.comparing(GeneratedTransaction::offset)
                .thenComparing(t -> t.request().idempotencyKey()));
        return List.copyOf(generated);
    }

    private static final List<ScenarioType> PLANTED_SHAPES = List.of(
            ScenarioType.VELOCITY_BURST,
            ScenarioType.CARD_TESTING,
            ScenarioType.AMOUNT_SPIKE,
            ScenarioType.GEO_IMPROBABLE,
            ScenarioType.ACCOUNT_DRAIN,
            ScenarioType.OFF_HOURS_NEW_DEVICE);

    // ------------------------------------------------------------- background

    private GeneratedTransaction ordinary(Random random, GeneratorAccount account, List<String> merchants) {
        Duration offset = someTimeInTheWindow(random);
        TransactionChannel channel = pick(random, ORDINARY_CHANNELS);

        return new GeneratedTransaction(
                request(
                        account,
                        familiarMerchant(random, account, merchants),
                        TransactionType.PURCHASE,
                        channel,
                        aroundBaseline(random, account),
                        homeCountryOf(account),
                        deviceFor(account, channel, random.nextInt(2)),
                        offset),
                offset,
                ScenarioType.NORMAL);
    }

    // ----------------------------------------------------------- planted shapes

    private List<GeneratedTransaction> plant(
            Random random, ScenarioType shape, GeneratorAccount account, List<String> merchants) {
        Duration start = someTimeInTheWindow(random);
        return switch (shape) {
            case VELOCITY_BURST -> velocityBurst(random, account, merchants, start);
            case CARD_TESTING -> cardTesting(random, account, merchants, start);
            case AMOUNT_SPIKE -> amountSpike(random, account, merchants, start);
            case GEO_IMPROBABLE -> geoImprobable(random, account, merchants, start);
            case ACCOUNT_DRAIN -> accountDrain(random, account, merchants, start);
            case OFF_HOURS_NEW_DEVICE -> offHoursNewDevice(random, account, merchants, start);
            case NORMAL -> throw new IllegalArgumentException("NORMAL is background, not a planted shape");
        };
    }

    /** Seven purchases inside ninety seconds. Invisible without per-account ordering. */
    private List<GeneratedTransaction> velocityBurst(
            Random random, GeneratorAccount account, List<String> merchants, Duration start) {
        List<GeneratedTransaction> burst = new ArrayList<>();
        Duration at = start;
        for (int i = 0; i < 7; i++) {
            at = at.plusSeconds(4L + random.nextInt(12));
            burst.add(new GeneratedTransaction(
                    request(
                            account,
                            pick(random, merchants),
                            TransactionType.PURCHASE,
                            TransactionChannel.CARD_NOT_PRESENT,
                            aroundBaseline(random, account),
                            homeCountryOf(account),
                            deviceFor(account, TransactionChannel.CARD_NOT_PRESENT, 0),
                            at),
                    at,
                    ScenarioType.VELOCITY_BURST));
        }
        return burst;
    }

    /**
     * Six trivial authorisations, then one large.
     *
     * <p>The small ones are the tell and are individually unremarkable, which is the point: a rule
     * that only looks at one transaction's amount cannot see this at all.
     */
    private List<GeneratedTransaction> cardTesting(
            Random random, GeneratorAccount account, List<String> merchants, Duration start) {
        List<GeneratedTransaction> run = new ArrayList<>();
        String merchant = pick(random, merchants);
        Duration at = start;

        for (int i = 0; i < 6; i++) {
            at = at.plusSeconds(20L + random.nextInt(60));
            run.add(new GeneratedTransaction(
                    request(
                            account,
                            merchant,
                            TransactionType.PURCHASE,
                            TransactionChannel.CARD_NOT_PRESENT,
                            money(0.50 + random.nextDouble() * 2.50),
                            homeCountryOf(account),
                            unknownDevice(account, 1),
                            at),
                    at,
                    ScenarioType.CARD_TESTING));
        }

        at = at.plusSeconds(90L + random.nextInt(240));
        run.add(new GeneratedTransaction(
                request(
                        account,
                        merchant,
                        TransactionType.PURCHASE,
                        TransactionChannel.CARD_NOT_PRESENT,
                        money(400.0 + random.nextDouble() * 500.0),
                        homeCountryOf(account),
                        unknownDevice(account, 1),
                        at),
                at,
                ScenarioType.CARD_TESTING));
        return run;
    }

    /** One purchase fifteen to thirty times this account's own baseline. */
    private List<GeneratedTransaction> amountSpike(
            Random random, GeneratorAccount account, List<String> merchants, Duration start) {
        double multiple = 15.0 + random.nextDouble() * 15.0;
        return List.of(new GeneratedTransaction(
                request(
                        account,
                        pick(random, merchants),
                        TransactionType.PURCHASE,
                        TransactionChannel.CARD_NOT_PRESENT,
                        money(baselineOf(account) * multiple),
                        homeCountryOf(account),
                        deviceFor(account, TransactionChannel.CARD_NOT_PRESENT, 0),
                        start),
                start,
                ScenarioType.AMOUNT_SPIKE));
    }

    /** Two card-present purchases, twenty minutes and a continent apart. */
    private List<GeneratedTransaction> geoImprobable(
            Random random, GeneratorAccount account, List<String> merchants, Duration start) {
        Duration second = start.plusMinutes(18L + random.nextInt(12));
        String elsewhere = pick(random, DISTANT_COUNTRIES);

        return List.of(
                new GeneratedTransaction(
                        request(
                                account,
                                pick(random, merchants),
                                TransactionType.PURCHASE,
                                TransactionChannel.CARD_PRESENT,
                                aroundBaseline(random, account),
                                homeCountryOf(account),
                                deviceFor(account, TransactionChannel.CARD_PRESENT, 0),
                                start),
                        start,
                        ScenarioType.GEO_IMPROBABLE),
                new GeneratedTransaction(
                        request(
                                account,
                                pick(random, merchants),
                                TransactionType.PURCHASE,
                                TransactionChannel.CARD_PRESENT,
                                aroundBaseline(random, account),
                                elsewhere,
                                unknownDevice(account, 2),
                                second),
                        second,
                        ScenarioType.GEO_IMPROBABLE));
    }

    /**
     * Three movements emptying most of the balance inside an hour.
     *
     * <p>Expressed as a fraction of the balance rather than as an amount, because that is the shape.
     * A fixed figure would be an ordinary withdrawal on a large account and impossible on a small
     * one.
     */
    private List<GeneratedTransaction> accountDrain(
            Random random, GeneratorAccount account, List<String> merchants, Duration start) {
        double remaining = account.balance().doubleValue() * (0.80 + random.nextDouble() * 0.15);
        List<GeneratedTransaction> drain = new ArrayList<>();
        Duration at = start;

        for (int i = 0; i < 3; i++) {
            at = at.plusMinutes(5L + random.nextInt(20));
            // Two roughly-equal bites, then whatever is left, so the last one
            // is not always the largest and the shape is the sequence rather
            // than any single row.
            double slice = i == 2 ? remaining : remaining * (0.30 + random.nextDouble() * 0.15);
            remaining -= slice;

            drain.add(new GeneratedTransaction(
                    request(
                            account,
                            pick(random, merchants),
                            i == 1 ? TransactionType.WITHDRAWAL : TransactionType.TRANSFER,
                            i == 1 ? TransactionChannel.ATM : TransactionChannel.ONLINE_TRANSFER,
                            money(Math.max(slice, 1.0)),
                            homeCountryOf(account),
                            i == 1 ? null : unknownDevice(account, 3),
                            at),
                    at,
                    ScenarioType.ACCOUNT_DRAIN));
        }
        return drain;
    }

    /** A purchase between 02:00 and 04:00 UTC from a device this account has never used. */
    private List<GeneratedTransaction> offHoursNewDevice(
            Random random, GeneratorAccount account, List<String> merchants, Duration start) {
        // Snapped to the small hours of whichever day the offset landed on,
        // rather than shifted by a fixed amount: "off hours" is a time of day
        // and has to survive the window being any length.
        //
        // This is an offset from windowStart, so it only lands at 02:00 UTC
        // because generate() anchors windowStart to a day boundary. That is the
        // precondition anchor() exists to guarantee; without it the arithmetic
        // below produces "two hours after whatever time of day the run started",
        // which is not off hours and was not off hours for as long as this
        // generator has existed.
        long days = start.toDays();
        Duration at = Duration.ofDays(days)
                .plusHours(2)
                .plusMinutes(random.nextInt(120))
                .plusSeconds(random.nextInt(60));

        return List.of(new GeneratedTransaction(
                request(
                        account,
                        pick(random, merchants),
                        TransactionType.PURCHASE,
                        TransactionChannel.CARD_NOT_PRESENT,
                        money(baselineOf(account) * (3.0 + random.nextDouble() * 4.0)),
                        homeCountryOf(account),
                        unknownDevice(account, 4),
                        at),
                at,
                ScenarioType.OFF_HOURS_NEW_DEVICE));
    }

    // -------------------------------------------------------------- machinery

    private TransactionRequest request(
            GeneratorAccount account,
            String merchantReference,
            TransactionType type,
            TransactionChannel channel,
            String amount,
            String country,
            String device,
            Duration offset) {

        return new TransactionRequest(
                idempotencyKey(account, offset),
                account.reference(),
                merchantReference,
                type,
                channel,
                new AmountRequest(amount, CURRENCY),
                country,
                device,
                windowStart.plus(offset));
    }

    /**
     * A key derived from the content, not from a counter.
     *
     * <p>This is what makes reloading free rather than dangerous: a second run with the same seed
     * and profile produces the same keys, and {@code transactions_idempotency_unique} rejects every
     * one of them. Re-running the generator is therefore a no-op rather than a doubled dataset, and
     * the guarantee is the database's rather than a flag someone remembered to check.
     *
     * <p>The sequence number is what makes it unique. Content alone is not: two ordinary purchases
     * on one account, at the same merchant, in the same second, for the same amount are entirely
     * possible in fourteen days of traffic, and the second would be silently rejected as a duplicate
     * of the first — leaving a dataset smaller than the manifest says.
     */
    private String idempotencyKey(GeneratorAccount account, Duration offset) {
        return "gen-%d-%06d-%s-%d".formatted(seed, sequence++, account.reference(), offset.toSeconds());
    }

    /**
     * Truncates the window start to a UTC day boundary.
     *
     * <p><strong>Two of this generator's guarantees are in conflict unless it does.</strong> Every
     * transaction is placed at a {@link Duration} offset from the window start, which is what makes
     * the dataset reproducible and what {@link ScenarioManifest}'s checksum covers. But
     * {@link ScenarioType#OFF_HOURS_NEW_DEVICE} is defined by a <em>time of day</em>, and a time of
     * day cannot be expressed as an offset from an arbitrary instant.
     *
     * <p>Left unanchored, the shape landed two hours after whatever time of day the run began. The
     * caller in production is {@code SeedRunner}, which passes {@code Instant.now()}, so it was
     * essentially never in the small hours — the planted "off-hours" transaction sat at an ordinary
     * hour and the {@code is_off_hours} feature it exists to exercise never fired on it. The
     * generator's own test did not catch it because it asserted the offset modulo 24 hours rather
     * than the hour the transaction occurred at, and its fixture window began at midnight, so the
     * two agreed exactly where the defect was invisible.
     *
     * <p><strong>Anchoring here rather than in the callers</strong>, because a precondition that
     * every caller must remember is a precondition that a future caller will not. The cost is that
     * generated traffic can begin up to 24 hours before the instant asked for — over a fourteen-day
     * window, a rounding nobody notices, and the alternative was a shape that did not mean what its
     * name said.
     */
    private static Instant anchor(Instant windowStart) {
        return windowStart.truncatedTo(ChronoUnit.DAYS);
    }

    private Duration someTimeInTheWindow(Random random) {
        return Duration.ofSeconds(random.nextInt((int) WINDOW.toSeconds()));
    }

    /**
     * This account's own typical spend, derived from its reference rather than drawn.
     *
     * <p>Stable across calls, which is what makes "far above what this account normally spends" a
     * property of the account rather than of the moment. Drawing it per transaction would make every
     * account identical in aggregate and a per-account baseline feature worthless.
     */
    private static double baselineOf(GeneratorAccount account) {
        int spread = Math.floorMod(account.reference().hashCode(), 60);
        return 12.0 + spread;
    }

    private static String aroundBaseline(Random random, GeneratorAccount account) {
        // Log-normal-ish rather than uniform: real spending has a long right
        // tail, and a uniform distribution makes an amount feature separate the
        // classes far too cleanly.
        double baseline = baselineOf(account);
        double factor = Math.exp(random.nextGaussian() * 0.55);
        return money(Math.max(0.50, baseline * factor));
    }

    private static String money(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** Most transactions go to one of a few merchants this account already uses. */
    private static String familiarMerchant(Random random, GeneratorAccount account, List<String> merchants) {
        if (random.nextInt(10) == 0) {
            return pick(random, merchants);
        }
        int base = Math.floorMod(account.reference().hashCode(), merchants.size());
        return merchants.get((base + random.nextInt(Math.min(4, merchants.size()))) % merchants.size());
    }

    private static String homeCountryOf(GeneratorAccount account) {
        return HOME_COUNTRIES.get(Math.floorMod(account.reference().hashCode() / 7, HOME_COUNTRIES.size()));
    }

    /**
     * @param slot 0 or 1 for the two devices this account habitually uses
     * @return null for a channel that has no device, which is a real answer rather than a gap
     */
    private static String deviceFor(GeneratorAccount account, TransactionChannel channel, int slot) {
        if (channel == TransactionChannel.ATM || channel == TransactionChannel.DIRECT_DEBIT) {
            return null;
        }
        return device(account.reference() + ":known:" + slot);
    }

    /** A device handle this account has never used, so "new device" is true rather than asserted. */
    private static String unknownDevice(GeneratorAccount account, int variant) {
        return device(account.reference() + ":unknown:" + variant);
    }

    private static String device(String material) {
        // A hash rather than a random draw, so the same account gets the same
        // handles in every run and "this device is new for this account" is a
        // fact about the dataset rather than an accident of ordering.
        long hash = 1125899906842597L;
        for (int i = 0; i < material.length(); i++) {
            hash = 31 * hash + material.charAt(i);
        }
        return "DEV-%012x".formatted(hash & 0xFFFF_FFFF_FFFFL);
    }

    private static <T> T pick(Random random, List<T> candidates) {
        return candidates.get(random.nextInt(candidates.size()));
    }
}
