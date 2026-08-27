/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.seed.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.la3679.sentinelflow.api.domain.TransactionChannel;
import io.github.la3679.sentinelflow.api.seed.SeedProfile;
import io.github.la3679.sentinelflow.api.web.dto.TransactionRequest;

/**
 * What the generator promises, asserted rather than assumed.
 *
 * <p>Determinism is a specification claim, not an observation: "reproduce it with seed 20260826" is
 * only a usable instruction if two runs genuinely agree, and the way that breaks is a stray clock
 * read or a hash-ordered collection, neither of which is visible by reading the code. The shapes are
 * asserted for the same reason — a scenario that quietly stopped being distinguishable from
 * background traffic would still generate rows, and every downstream evaluation would silently
 * become meaningless.
 *
 * <p>A unit test, because the generator has no database and no clock. That is itself the design
 * being tested.
 */
class ScenarioGeneratorTests {

    private static final long SEED = 20_260_826L;
    private static final Instant WINDOW_START = Instant.parse("2026-08-12T00:00:00Z");

    private static final List<GeneratorAccount> ACCOUNTS = List.of(
            new GeneratorAccount("ACC-000001", new BigDecimal("1000.0000")),
            new GeneratorAccount("ACC-000002", new BigDecimal("2500.0000")),
            new GeneratorAccount("ACC-000003", new BigDecimal("400.0000")),
            new GeneratorAccount("ACC-000004", new BigDecimal("8000.0000")));

    private static final List<String> MERCHANTS = List.of("MER-0001", "MER-0002", "MER-0003", "MER-0004", "MER-0005");

    private static List<GeneratedTransaction> generate(long seed, SeedProfile profile) {
        return new ScenarioGenerator(seed, profile).generate(WINDOW_START, ACCOUNTS, MERCHANTS);
    }

    private static List<GeneratedTransaction> of(List<GeneratedTransaction> all, ScenarioType shape) {
        return all.stream().filter(t -> t.scenario() == shape).toList();
    }

    // ------------------------------------------------------------ determinism

    @Test
    @DisplayName("the same seed produces byte-identical traffic")
    void isDeterministic() {
        List<GeneratedTransaction> first = generate(SEED, SeedProfile.CI);
        List<GeneratedTransaction> second = generate(SEED, SeedProfile.CI);

        // Record equality, so this compares every field of every request -
        // amount strings included, which is where a floating-point formatting
        // difference would show up.
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("two calls on one generator agree, so the sequence counter resets")
    void isRepeatableOnOneInstance() {
        ScenarioGenerator generator = new ScenarioGenerator(SEED, SeedProfile.CI);

        // The counter that makes idempotency keys unique is instance state. If
        // it did not reset, the second call would produce the same traffic with
        // different keys - which reads as determinism until something hashes
        // the keys.
        assertThat(generator.generate(WINDOW_START, ACCOUNTS, MERCHANTS))
                .isEqualTo(generator.generate(WINDOW_START, ACCOUNTS, MERCHANTS));
    }

    @Test
    @DisplayName("a different seed produces different traffic")
    void seedActuallyVaries() {
        assertThat(generate(SEED + 1, SeedProfile.CI)).isNotEqualTo(generate(SEED, SeedProfile.CI));
    }

    @Test
    @DisplayName("the window start moves every transaction and changes nothing else")
    void windowStartOnlyShiftsTime() {
        List<GeneratedTransaction> here = generate(SEED, SeedProfile.CI);
        List<GeneratedTransaction> later = new ScenarioGenerator(SEED, SeedProfile.CI)
                .generate(WINDOW_START.plus(Duration.ofDays(30)), ACCOUNTS, MERCHANTS);

        assertThat(later).hasSameSizeAs(here);
        for (int i = 0; i < here.size(); i++) {
            // Same traffic, moved. The offsets are what the checksum covers,
            // and this is why they can be.
            assertThat(later.get(i).offset()).isEqualTo(here.get(i).offset());
            assertThat(later.get(i).request().idempotencyKey())
                    .isEqualTo(here.get(i).request().idempotencyKey());
            assertThat(later.get(i).request().occurredAt())
                    .isEqualTo(here.get(i).request().occurredAt().plus(Duration.ofDays(30)));
        }
    }

    // ---------------------------------------------------------------- shape

    @Test
    @DisplayName("every idempotency key is unique, or the dataset silently shrinks on load")
    void keysAreUnique() {
        List<GeneratedTransaction> generated = generate(SEED, SeedProfile.DEMO);

        Set<String> keys =
                generated.stream().map(t -> t.request().idempotencyKey()).collect(Collectors.toSet());

        // A collision is not an error at load time - the constraint rejects it
        // as a duplicate - which is exactly why it has to be caught here. The
        // symptom otherwise is a manifest that overstates what was written.
        assertThat(keys).hasSize(generated.size());
    }

    @Test
    @DisplayName("every request satisfies the constraints the HTTP boundary enforces")
    void requestsAreWellFormed() {
        for (GeneratedTransaction transaction : generate(SEED, SeedProfile.CI)) {
            TransactionRequest request = transaction.request();

            // Generated data goes through TransactionWriter, so anything the
            // boundary would reject fails at load time in a loop, one row at a
            // time. Cheaper to find here.
            assertThat(request.idempotencyKey()).hasSizeBetween(8, 128);
            assertThat(request.accountReference()).matches("^ACC-[0-9]{6}$");
            assertThat(request.merchantReference()).matches("^MER-[0-9]{4}$");
            assertThat(request.originCountry()).matches("^[A-Z]{2}$");
            assertThat(request.amount().value()).matches("^-?(0|[1-9][0-9]{0,14})(\\.[0-9]{1,4})?$");
            assertThat(new BigDecimal(request.amount().value())).isNotEqualByComparingTo(BigDecimal.ZERO);
            if (request.deviceReference() != null) {
                assertThat(request.deviceReference()).matches("^DEV-[0-9a-f]{12}$");
            }
        }
    }

    @Test
    @DisplayName("the result is ordered by when it occurred")
    void isOrdered() {
        // The loader writes in this order, and per-account ordering is the
        // guarantee the pipeline is built around.
        assertThat(generate(SEED, SeedProfile.CI))
                .isSortedAccordingTo(Comparator.comparing(GeneratedTransaction::offset));
    }

    @Test
    @DisplayName("every planted shape appears, so no profile silently stops testing one")
    void everyShapeIsPlanted() {
        Map<ScenarioType, Long> counts = generate(SEED, SeedProfile.CI).stream()
                .collect(Collectors.groupingBy(GeneratedTransaction::scenario, Collectors.counting()));

        assertThat(counts).containsOnlyKeys(ScenarioType.values());
        assertThat(counts.get(ScenarioType.NORMAL)).isEqualTo(SeedProfile.CI.transactions());
    }

    // ------------------------------------------------------- the shapes hold

    @Test
    @DisplayName("a velocity burst is many transactions on one account inside two minutes")
    void velocityBurstIsDense() {
        Map<String, List<GeneratedTransaction>> byAccount =
                of(generate(SEED, SeedProfile.CI), ScenarioType.VELOCITY_BURST).stream()
                        .collect(Collectors.groupingBy(t -> t.request().accountReference()));

        assertThat(byAccount).isNotEmpty();
        byAccount.values().forEach(burst -> {
            // Seven per burst, and several bursts may land on one account, so
            // the count is asserted as a multiple rather than exactly.
            assertThat(burst.size() % 7).isZero();

            // Density is the shape, and the assertion has to survive two bursts
            // landing on one account weeks apart: somewhere in this account's
            // burst traffic there is a two-minute window holding seven of them.
            List<Long> seconds =
                    burst.stream().map(t -> t.offset().toSeconds()).sorted().toList();

            int densest = 0;
            for (int i = 0; i < seconds.size(); i++) {
                int inWindow = 0;
                for (int j = i; j < seconds.size() && seconds.get(j) - seconds.get(i) <= 120; j++) {
                    inWindow++;
                }
                densest = Math.max(densest, inWindow);
            }
            assertThat(densest).isGreaterThanOrEqualTo(7);
        });
    }

    @Test
    @DisplayName("card testing is a run of trivial amounts ending in a large one")
    void cardTestingEscalates() {
        List<GeneratedTransaction> run = of(generate(SEED, SeedProfile.CI), ScenarioType.CARD_TESTING);
        assertThat(run).isNotEmpty();

        Map<String, List<GeneratedTransaction>> byKey =
                run.stream().collect(Collectors.groupingBy(t -> t.request().accountReference()));

        byKey.values().forEach(perAccount -> {
            List<BigDecimal> amounts = perAccount.stream()
                    .sorted(Comparator.comparing(GeneratedTransaction::offset))
                    .map(t -> new BigDecimal(t.request().amount().value()))
                    .toList();

            // The small ones are the tell and are individually unremarkable,
            // which is the point: a rule reading one amount cannot see this.
            long trivial = amounts.stream()
                    .filter(a -> a.compareTo(new BigDecimal("5.00")) < 0)
                    .count();
            long large = amounts.stream()
                    .filter(a -> a.compareTo(new BigDecimal("400.00")) >= 0)
                    .count();

            assertThat(trivial).isGreaterThanOrEqualTo(6);
            assertThat(large).isGreaterThanOrEqualTo(1);
        });
        assertThat(run).allMatch(t -> t.request().channel() == TransactionChannel.CARD_NOT_PRESENT);
    }

    @Test
    @DisplayName("an amount spike is far above what that account normally spends")
    void amountSpikeIsRelative() {
        List<GeneratedTransaction> all = generate(SEED, SeedProfile.CI);
        Map<String, Double> ordinaryMean = of(all, ScenarioType.NORMAL).stream()
                .collect(Collectors.groupingBy(
                        t -> t.request().accountReference(),
                        Collectors.averagingDouble(
                                t -> Double.parseDouble(t.request().amount().value()))));

        List<GeneratedTransaction> spikes = of(all, ScenarioType.AMOUNT_SPIKE);
        assertThat(spikes).isNotEmpty();

        for (GeneratedTransaction spike : spikes) {
            Double mean = ordinaryMean.get(spike.request().accountReference());
            if (mean == null) {
                // A small profile may not give every account background
                // traffic. Nothing to compare against, and inventing a
                // comparison would be worse than skipping one.
                continue;
            }
            // Relative, not absolute. An absolute threshold would make the
            // shape a property of the currency rather than of the account.
            assertThat(Double.parseDouble(spike.request().amount().value())).isGreaterThan(mean * 5);
        }
    }

    @Test
    @DisplayName("an improbable journey is two card-present purchases, minutes and a continent apart")
    void geoImprobableIsImprobable() {
        List<GeneratedTransaction> pairs = of(generate(SEED, SeedProfile.CI), ScenarioType.GEO_IMPROBABLE);
        assertThat(pairs).isNotEmpty().hasSizeGreaterThanOrEqualTo(2);

        Set<String> countries =
                pairs.stream().map(t -> t.request().originCountry()).collect(Collectors.toSet());
        assertThat(countries).hasSizeGreaterThan(1);
        assertThat(pairs).allMatch(t -> t.request().channel() == TransactionChannel.CARD_PRESENT);
    }

    @Test
    @DisplayName("a drain empties most of the balance, and is a fraction rather than a figure")
    void drainIsProportional() {
        Map<String, BigDecimal> balances =
                ACCOUNTS.stream().collect(Collectors.toMap(GeneratorAccount::reference, GeneratorAccount::balance));

        Map<String, Double> drained = of(generate(SEED, SeedProfile.CI), ScenarioType.ACCOUNT_DRAIN).stream()
                .collect(Collectors.groupingBy(
                        t -> t.request().accountReference(),
                        Collectors.summingDouble(
                                t -> Double.parseDouble(t.request().amount().value()))));

        assertThat(drained).isNotEmpty();
        drained.forEach((account, total) -> {
            double balance = balances.get(account).doubleValue();
            // At least half, because "most of it" is what makes this a drain
            // rather than a large withdrawal. Several drains may land on one
            // account, so there is no upper bound to assert here.
            assertThat(total).isGreaterThan(balance * 0.5);
        });
    }

    @Test
    @DisplayName("off-hours traffic is in the small hours and on a device the account has never used")
    void offHoursUsesANewDevice() {
        List<GeneratedTransaction> all = generate(SEED, SeedProfile.CI);
        Map<String, Set<String>> knownDevices = of(all, ScenarioType.NORMAL).stream()
                .filter(t -> t.request().deviceReference() != null)
                .collect(Collectors.groupingBy(
                        t -> t.request().accountReference(),
                        Collectors.mapping(t -> t.request().deviceReference(), Collectors.toSet())));

        List<GeneratedTransaction> offHours = of(all, ScenarioType.OFF_HOURS_NEW_DEVICE);
        assertThat(offHours).isNotEmpty();

        for (GeneratedTransaction transaction : offHours) {
            // The hour it OCCURRED at, not the offset modulo 24. Those agree
            // only when the window begins at midnight, which is exactly what
            // this suite's fixture does — so the previous version of this
            // assertion passed while the shape landed at an ordinary hour in
            // every real run. See offHoursIsOffHoursFromAnyWindowStart.
            assertThat(hourOf(transaction))
                    .as("a purchase at 14:00 is not an off-hours purchase, whatever it is labelled")
                    .isBetween(2, 3);

            Set<String> known = knownDevices.getOrDefault(transaction.request().accountReference(), Set.of());
            // "New device" has to be true of the dataset, not merely asserted
            // in a comment: a handle that the account's ordinary traffic also
            // uses would make the feature it exercises meaningless.
            assertThat(known).doesNotContain(transaction.request().deviceReference());
        }
    }

    @Test
    @DisplayName("off-hours is off-hours whatever time of day the run started")
    void offHoursIsOffHoursFromAnyWindowStart() {
        // The production caller is SeedRunner, which passes Instant.now(), so a
        // midnight window start is the one case that essentially never happens.
        // Every instant here is a time of day a real run could begin at.
        for (String start : List.of(
                "2026-08-12T00:00:00Z", "2026-08-12T12:00:00Z", "2026-08-12T17:23:41Z", "2026-08-12T23:59:59Z")) {
            List<GeneratedTransaction> offHours = of(
                    new ScenarioGenerator(SEED, SeedProfile.CI).generate(Instant.parse(start), ACCOUNTS, MERCHANTS),
                    ScenarioType.OFF_HOURS_NEW_DEVICE);

            assertThat(offHours)
                    .as("nothing to assert about if the shape was not planted")
                    .isNotEmpty();
            assertThat(offHours).as("from a window starting at %s", start).allSatisfy(transaction -> assertThat(
                            hourOf(transaction))
                    .as("the is_off_hours feature fires on UTC hours 2 to 4; a shape planted "
                            + "outside them exercises nothing it was created to exercise")
                    .isBetween(2, 3));
        }
    }

    @Test
    @DisplayName("the window is anchored to a day, so the same seed gives the same offsets at any hour")
    void anchoringKeepsOffsetsStable() {
        List<GeneratedTransaction> midnight = generate(SEED, SeedProfile.CI);
        List<GeneratedTransaction> afternoon = new ScenarioGenerator(SEED, SeedProfile.CI)
                .generate(WINDOW_START.plus(Duration.ofHours(14)), ACCOUNTS, MERCHANTS);

        assertThat(afternoon)
                .as("without anchoring, a run at 14:00 would produce different offsets from one at "
                        + "midnight and ScenarioManifest's checksum — which covers offsets — would "
                        + "differ between two runs that generated the same dataset")
                .isEqualTo(midnight);
    }

    private static int hourOf(GeneratedTransaction transaction) {
        return transaction.request().occurredAt().atZone(ZoneOffset.UTC).getHour();
    }

    // -------------------------------------------------------------- refusals

    @Test
    @DisplayName("generating against nothing fails loudly rather than producing an empty dataset")
    void refusesEmptyInputs() {
        ScenarioGenerator generator = new ScenarioGenerator(SEED, SeedProfile.CI);

        assertThatThrownBy(() -> generator.generate(WINDOW_START, List.of(), MERCHANTS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.generate(WINDOW_START, ACCOUNTS, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a bigger profile produces more traffic")
    void profileScales() {
        Function<SeedProfile, Integer> size = profile -> generate(SEED, profile).size();

        assertThat(size.apply(SeedProfile.DEMO)).isGreaterThan(size.apply(SeedProfile.CI));
    }
}
