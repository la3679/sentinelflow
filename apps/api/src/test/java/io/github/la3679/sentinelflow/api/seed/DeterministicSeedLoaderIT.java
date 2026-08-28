/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.la3679.sentinelflow.api.support.AbstractPostgresTest;

/**
 * The two claims the seed makes: it is reproducible, and running it twice does not double it.
 *
 * <p>Both are claims about a second run, which is exactly the kind that goes untested and then
 * fails in front of someone.
 *
 * <p>Each test starts from an empty demo dataset. The reference data V1 inserts - the four roles
 * and the system principal - is deliberately left in place, because it is schema rather than demo
 * data and the foreign keys on {@code alert_actions} and {@code audit_log} depend on it.
 */
class DeterministicSeedLoaderIT extends AbstractPostgresTest {

    private static final long SEED = 20_260_826L;

    /**
     * Any value: this suite asserts what the seed writes, not what a password is.
     *
     * <p>It is required rather than optional because {@code SeedProperties} refuses to be
     * constructed with a blank one when seeding is enabled - which is the point of that validation,
     * and is why it is stated here rather than defaulted away.
     */
    private static final String OPERATOR_PASSWORD = "a-password-for-a-test-only";

    @Autowired
    private DeterministicSeedLoader loader;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @BeforeEach
    void emptyTheDemoData() {
        // CASCADE reaches the assessments, alerts and actions that other suites
        // in this fork left behind. Roles and the system principal survive: the
        // seed needs the roles to grant, and every other suite needs the
        // principal to attribute an automated action to.
        jdbc.execute("""
                TRUNCATE transactions, risk_assessments, alerts, alert_actions, analyst_feedback,
                         accounts, customers, merchants
                CASCADE
                """);
        jdbc.update("DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE username <> 'system')");
        jdbc.update("DELETE FROM users WHERE username <> 'system'");
    }

    private SeedManifest seed(long seed, SeedProfile profile) {
        // Parties only. This suite is about the party loader, and generating
        // traffic here would put thousands of transactions behind every
        // assertion for nothing - ScenarioLoaderIT covers that half.
        return transactions.execute(
                status -> loader.load(new SeedProperties(true, seed, profile, OPERATOR_PASSWORD, false)));
    }

    private List<Map<String, Object>> customerRows() {
        return jdbc.queryForList(
                "SELECT customer_reference, country_code, risk_tier FROM customers ORDER BY customer_reference");
    }

    @Test
    @DisplayName("the same seed produces the same dataset, checksum included")
    void sameSeedProducesTheSameDataset() {
        SeedManifest first = seed(SEED, SeedProfile.CI);
        List<Map<String, Object>> firstRows = customerRows();

        emptyTheDemoData();
        SeedManifest second = seed(SEED, SeedProfile.CI);

        // The checksum is over the generated references in generation order.
        // Identifiers are deliberately excluded: they are UUIDv7 and carry the
        // wall-clock time they were minted, so two identical runs necessarily
        // differ there and hashing them would prove nothing.
        assertThat(second.checksum()).isEqualTo(first.checksum()).isNotEmpty();
        assertThat(second.generatorVersion()).isEqualTo(SeedManifest.GENERATOR_VERSION);
        // The row contents, which the reference checksum cannot see.
        assertThat(customerRows()).isEqualTo(firstRows);
    }

    @Test
    @DisplayName("a different seed produces different rows behind the same references")
    void differentSeedProducesDifferentRows() {
        seed(SEED, SeedProfile.CI);
        List<Map<String, Object>> firstRows = customerRows();

        emptyTheDemoData();
        seed(SEED + 1, SeedProfile.CI);

        // References are positional, so they and their checksum are unchanged.
        // Countries, tiers, balances and merchant names are drawn from the
        // seeded source and are not.
        assertThat(customerRows()).isNotEqualTo(firstRows);
        assertThat(customerRows()).hasSameSizeAs(firstRows);
    }

    @Test
    @DisplayName("the profile decides the counts, and the manifest reports what was written")
    void profileDecidesTheCounts() {
        SeedManifest manifest = seed(SEED, SeedProfile.CI);

        assertThat(manifest.skipped()).isFalse();
        assertThat(manifest.customers()).isEqualTo(SeedProfile.CI.customers());
        assertThat(manifest.merchants()).isEqualTo(SeedProfile.CI.merchants());
        assertThat(manifest.accounts()).isEqualTo(SeedProfile.CI.accounts());
        assertThat(manifest.analysts()).isPositive();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers", Integer.class))
                .isEqualTo(SeedProfile.CI.customers());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts", Integer.class))
                .isEqualTo(SeedProfile.CI.accounts());
    }

    @Test
    @DisplayName("a second run over seeded data writes nothing rather than doubling or failing")
    void secondRunIsANoOp() {
        SeedManifest first = seed(SEED, SeedProfile.CI);
        assertThat(first.skipped()).isFalse();

        // Exactly what a developer running the command twice does. It must not
        // duplicate, and it must not fail either: they are asking for the data
        // to be there, not for an error.
        SeedManifest second = seed(SEED, SeedProfile.CI);

        assertThat(second).isNotNull();
        assertThat(second.skipped()).isTrue();
        assertThat(second.totalRows()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers", Integer.class))
                .isEqualTo(SeedProfile.CI.customers());
    }

    @Test
    @DisplayName("analyst logins are stable across runs and hold exactly one role each")
    void analystLoginsAreStable() {
        seed(SEED, SeedProfile.CI);

        List<String> usernames = jdbc.queryForList(
                "SELECT username FROM users WHERE username <> 'system' ORDER BY username", String.class);
        assertThat(usernames).containsExactly("administrator.one", "analyst.one", "analyst.two", "auditor.one");

        // A demo script naming analyst.one has to find it after the seed
        // changes, so these are fixed rather than drawn from the random source.
        Integer grants = jdbc.queryForObject(
                "SELECT count(*) FROM user_roles ur JOIN users u ON u.id = ur.user_id WHERE u.username <> 'system'",
                Integer.class);
        assertThat(grants).isEqualTo(usernames.size());
    }

    @Test
    @DisplayName("every seeded operator gets one credential, and the system principal gets none")
    void seededOperatorsCanLogInAndTheSystemPrincipalCannot() {
        seed(SEED, SeedProfile.CI);

        List<String> withCredentials = jdbc.queryForList("""
                SELECT u.username FROM users u
                  JOIN user_credentials c ON c.user_id = u.id
                 ORDER BY u.username
                """, String.class);
        assertThat(withCredentials).containsExactly("administrator.one", "analyst.one", "analyst.two", "auditor.one");

        // ADR-0012 section 2. The principal that attributes automated actions
        // must never be able to authenticate, and the absence of a row is what
        // makes that structural rather than a rule somebody remembers.
        assertThat(withCredentials).doesNotContain("system");

        List<String> hashes = jdbc.queryForList("SELECT password_hash FROM user_credentials", String.class);
        assertThat(hashes)
                .as("an algorithm-identified hash, never the password. The column's CHECK refuses "
                        + "anything without the {algorithm} prefix, and this asserts the encoder "
                        + "produces one rather than that the constraint exists.")
                .allMatch(hash -> hash.startsWith("{bcrypt}"))
                .doesNotContain(OPERATOR_PASSWORD);
    }

    @Test
    @DisplayName("every generated value is synthetic and matches the documented reference patterns")
    void generatedDataIsSynthetic() {
        seed(SEED, SeedProfile.CI);

        // The CHECK constraints already enforce these patterns; asserting them
        // here is about the loader, not the schema. A loader that started
        // emitting something resembling a real account number would still
        // satisfy every constraint in V2.
        assertThat(jdbc.queryForList("SELECT customer_reference FROM customers", String.class))
                .isNotEmpty()
                .allMatch(reference -> reference.matches("^CUS-\\d{6}$"));
        assertThat(jdbc.queryForList("SELECT account_reference FROM accounts", String.class))
                .isNotEmpty()
                .allMatch(reference -> reference.matches("^ACC-\\d{6}$"));
        assertThat(jdbc.queryForList("SELECT merchant_reference FROM merchants", String.class))
                .isNotEmpty()
                .allMatch(reference -> reference.matches("^MER-\\d{4}$"));

        // No table the seed writes has a column for a name, an address, a date
        // of birth or a card number. This asserts the schema keeps it that way:
        // a column added later is where seed data would start carrying
        // something it should not.
        List<String> personalColumns = jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name IN ('customers', 'accounts')
                  AND column_name ~ '(first_name|last_name|full_name|email|phone|address|dob|date_of_birth|pan|card)'
                """, String.class);
        assertThat(personalColumns).isEmpty();
    }
}
