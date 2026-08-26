/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.seed.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import io.github.la3679.sentinelflow.api.seed.SeedProfile;
import io.github.la3679.sentinelflow.api.support.AbstractPostgresTest;
import io.github.la3679.sentinelflow.api.support.SchemaFixtures;

/**
 * What loading generated traffic actually does to the database.
 *
 * <p>Three things are worth proving here and none of them is visible in the generator's own tests:
 * that generated traffic goes through the same write path as posted traffic and therefore produces
 * outbox rows, that reloading is genuinely harmless rather than merely guarded, and that the
 * manifest describes what happened rather than what was asked for.
 *
 * <p>The relay is disabled. Otherwise a scheduled thread drains the outbox while the assertions are
 * counting it, and the suite passes or fails on timing.
 */
@TestPropertySource(properties = "sentinelflow.outbox.enabled=false")
class ScenarioLoaderIT extends AbstractPostgresTest {

    private static final long SEED = 20_260_826L;
    private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");

    @Autowired
    private ScenarioLoader loader;

    @Autowired
    private JdbcTemplate jdbc;

    private SchemaFixtures fixtures;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE transactions, outbox_events CASCADE");
        jdbc.execute("TRUNCATE accounts, merchants, customers CASCADE");
        fixtures = new SchemaFixtures(jdbc);
    }

    /**
     * Enough parties for the CI profile to have somewhere to put its traffic.
     *
     * <p><strong>Fixed references, not {@code SchemaFixtures}' generated ones.</strong> The
     * generator's output depends on the account and merchant references it is given — deliberately,
     * because an account's habits are derived from its reference — so a run over
     * {@code ACC-000042} produces different traffic from one over {@code ACC-000043}. Reproducibility
     * can only be asserted if the input is the same both times, and a shared counter guarantees it
     * is not.
     */
    private void seedParties(int accounts, int merchants) {
        UUID customer = fixtures.insertCustomer();
        for (int i = 0; i < accounts; i++) {
            jdbc.update("""
                    INSERT INTO accounts (customer_id, account_reference, currency, balance, status, opened_at)
                    VALUES (?, ?, 'GBP', 1500.0000, 'ACTIVE', now())
                    """, customer, "ACC-%06d".formatted(900_000 + i));
        }
        for (int i = 0; i < merchants; i++) {
            jdbc.update("""
                    INSERT INTO merchants (merchant_reference, name, category_code, country_code)
                    VALUES (?, 'Synthetic Supplies', '5411', 'GB')
                    """, "MER-%04d".formatted(9_000 + i));
        }
    }

    private long count(String table) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("generated traffic is written, and every transaction has its outbox event")
    void writesTransactionsAndEvents() {
        seedParties(6, 5);

        ScenarioManifest manifest = loader.load(SEED, SeedProfile.CI, NOW);

        assertThat(manifest.skipped()).isFalse();
        assertThat(manifest.written()).isEqualTo(manifest.generated()).isPositive();
        assertThat(count("transactions")).isEqualTo(manifest.written());

        // One event per transaction, in the same database transaction as the
        // row. This is the point of going through TransactionWriter rather
        // than inserting: a private path would produce rows the pipeline never
        // sees, and a seeded demo would demonstrate nothing.
        assertThat(count("outbox_events")).isEqualTo(manifest.written());
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT event_type) FROM outbox_events", Long.class))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("generated traffic is marked as generated, for ever, in the row itself")
    void marksTheSource() {
        seedParties(6, 5);
        loader.load(SEED, SeedProfile.CI, NOW);

        assertThat(jdbc.queryForList("SELECT DISTINCT ingestion_source FROM transactions", String.class))
                .containsExactly("GENERATOR");
    }

    @Test
    @DisplayName("traffic lands inside the generated window, ending at the instant it was given")
    void placesTrafficInTheWindow() {
        seedParties(6, 5);
        loader.load(SEED, SeedProfile.CI, NOW);

        Instant earliest = jdbc.queryForObject("SELECT min(occurred_at) FROM transactions", Instant.class);
        Instant latest = jdbc.queryForObject("SELECT max(occurred_at) FROM transactions", Instant.class);

        // occurred_at and ingested_at are separate facts and both are kept
        // (V3). A replayed scenario occurred when the scenario says it did;
        // collapsing the two would make every generated transaction look like
        // it happened at import time and destroy every velocity feature
        // computed from it.
        assertThat(earliest).isAfterOrEqualTo(NOW.minus(java.time.Duration.ofDays(15)));
        assertThat(latest).isBeforeOrEqualTo(NOW);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions WHERE ingested_at < occurred_at", Long.class))
                .isZero();
    }

    @Test
    @DisplayName("the manifest reports the label distribution, and the database does not")
    void keepsLabelsOutOfTheDatabase() {
        seedParties(6, 5);

        ScenarioManifest manifest = loader.load(SEED, SeedProfile.CI, NOW);

        assertThat(manifest.labelDistribution()).containsOnlyKeys(ScenarioType.values());
        assertThat(manifest.planted()).isPositive();

        // The leak this design exists to prevent. A label column would be
        // information that only exists after the fact, sitting next to the row
        // a model is about to be asked to score.
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'transactions'", String.class);
        assertThat(columns)
                .noneMatch(column -> column.contains("label")
                        || column.contains("scenario")
                        || column.contains("suspicious")
                        || column.contains("fraud"));
    }

    @Test
    @DisplayName("loading twice is a no-op, and says so")
    void reloadingIsHarmless() {
        seedParties(6, 5);
        ScenarioManifest first = loader.load(SEED, SeedProfile.CI, NOW);

        ScenarioManifest second = loader.load(SEED, SeedProfile.CI, NOW);

        assertThat(second.skipped()).isTrue();
        assertThat(second.written()).isZero();
        assertThat(count("transactions")).isEqualTo(first.written());
    }

    @Test
    @DisplayName("the constraint is the guarantee, not the guard: a bypassed check still cannot duplicate")
    void theDatabaseIsWhatStopsADuplicate() {
        seedParties(6, 5);
        ScenarioManifest first = loader.load(SEED, SeedProfile.CI, NOW);

        // Defeat the short-circuit so the write path itself is exercised. The
        // fast skip is an optimisation; transactions_idempotency_unique is the
        // guarantee, and this is the assertion that says which is which.
        jdbc.update("UPDATE transactions SET ingestion_source = 'BATCH'");
        ScenarioManifest second = loader.load(SEED, SeedProfile.CI, NOW);

        assertThat(second.skipped()).isFalse();
        assertThat(second.generated()).isEqualTo(first.generated());
        assertThat(second.written()).isZero();
        assertThat(count("transactions")).isEqualTo(first.written());
    }

    @Test
    @DisplayName("the same seed produces the same checksum on a different database, a day later")
    void checksumIsReproducible() {
        seedParties(6, 5);
        ScenarioManifest first = loader.load(SEED, SeedProfile.CI, NOW);

        reset();
        seedParties(6, 5);
        // A fresh database and a different wall clock. Every identifier and
        // every reference the database assigns is new, and the window has
        // moved a day - so anything the checksum covered that came from the
        // database would change it.
        ScenarioManifest second = loader.load(SEED, SeedProfile.CI, NOW.plusSeconds(86_400));

        assertThat(second.checksum()).isEqualTo(first.checksum()).matches("^[0-9a-f]{64}$");
        assertThat(second.generated()).isEqualTo(first.generated());
        assertThat(second.labelDistribution()).isEqualTo(first.labelDistribution());
    }

    @Test
    @DisplayName("a different seed produces a different checksum")
    void checksumTracksTheSeed() {
        seedParties(6, 5);
        ScenarioManifest first = loader.load(SEED, SeedProfile.CI, NOW);

        reset();
        seedParties(6, 5);
        ScenarioManifest other = loader.load(SEED + 1, SeedProfile.CI, NOW);

        // A checksum that did not move with the seed would be a checksum over
        // something other than the dataset.
        assertThat(other.checksum()).isNotEqualTo(first.checksum());
    }

    @Test
    @DisplayName("generating with no parties fails with an error that names the fix")
    void refusesWithoutParties() {
        assertThatThrownBy(() -> loader.load(SEED, SeedProfile.CI, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SENTINELFLOW_SEED_ENABLED");
    }
}
