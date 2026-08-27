/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.seed.scenario;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import io.github.la3679.sentinelflow.api.seed.SeedProfile;

/**
 * Producing the generated dataset, in one place, for everyone who needs it.
 *
 * <p>Two callers need the same list of {@link GeneratedTransaction}: {@link ScenarioLoader}, which
 * writes it into the database, and the labelled training export, which needs it again later to
 * recover <em>which shape</em> each stored transaction was planted as — because
 * {@link ScenarioType} never enters the schema, deliberately.
 *
 * <p><strong>Both must regenerate identically or the export mislabels rows.</strong> The generator
 * is deterministic in its seed, its profile and its inputs, so "identically" means reading the
 * accounts and merchants in exactly the same order as well. Two copies of those two queries would
 * be two chances for an {@code ORDER BY} to drift, and the failure would be silent: every row would
 * still get a label, just not its own. A model trained on shuffled labels is not obviously broken —
 * it is quietly mediocre, which is much harder to attribute.
 *
 * <p>So the queries live here once, and both callers get the same list or neither does.
 */
@Service
public class ScenarioDataset {

    /**
     * How far back the generated window reaches from its end instant. Fourteen days, because every
     * planted shape needs history to be visible against and a shorter window would leave the
     * account-relative features with nothing to be relative to.
     */
    public static final Duration WINDOW = Duration.ofDays(14);

    private final JdbcTemplate jdbc;

    public ScenarioDataset(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Regenerates the dataset for a seed and profile over whatever parties are present.
     *
     * <p>Pure with respect to the database in the sense that matters: it reads, and the same
     * database with the same seed and profile always produces the same list, in the same order,
     * with the same idempotency keys. The keys are derived from the seed and a sequence number
     * rather than from {@code windowEnd}, so an export run days after the load still recovers the
     * same keys and can join to the stored rows.
     *
     * @param windowEnd the end of the generated window; traffic is placed in the {@link #WINDOW}
     *     before it
     * @throws IllegalStateException if there are no parties to generate against
     */
    public List<GeneratedTransaction> generate(long seed, SeedProfile profile, Instant windowEnd) {
        List<GeneratorAccount> accounts = readAccounts();
        List<String> merchants = readMerchants();
        if (accounts.isEmpty() || merchants.isEmpty()) {
            // A clearer failure than the generator's own: the caller's mistake
            // is almost always that the party seed did not run, and saying so
            // is worth more than "cannot generate without accounts".
            throw new IllegalStateException("No accounts or merchants to generate against. Run the party seed first: "
                    + "SENTINELFLOW_SEED_ENABLED=true");
        }
        return new ScenarioGenerator(seed, profile).generate(windowEnd.minus(WINDOW), accounts, merchants);
    }

    private List<GeneratorAccount> readAccounts() {
        List<GeneratorAccount> accounts = new ArrayList<>();
        jdbc.query(
                "SELECT account_reference, balance FROM accounts WHERE status = 'ACTIVE' ORDER BY account_reference",
                rs -> {
                    accounts.add(new GeneratorAccount(rs.getString("account_reference"), rs.getBigDecimal("balance")));
                });
        return accounts;
    }

    private List<String> readMerchants() {
        return jdbc.queryForList("SELECT merchant_reference FROM merchants ORDER BY merchant_reference", String.class);
    }

    /**
     * SHA-256 over what the generator described, in order.
     *
     * <p>Everything hashed is deterministic: the offset rather than the instant, the references
     * rather than the identifiers, the amount as the string that was sent. Nothing the database
     * assigned goes in, for the reason recorded on {@link ScenarioManifest} — a checksum that
     * covered a generated identifier would differ between two runs that produced identical data.
     */
    public static String checksumOf(List<GeneratedTransaction> generated) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            // Every JRE is required to provide SHA-256. Wrapping rather than
            // declaring, so callers are not made to handle something that
            // cannot happen.
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }

        for (GeneratedTransaction transaction : generated) {
            var request = transaction.request();
            // \n, never %n: %n is the platform line separator, so the same
            // dataset would hash differently on Windows and Linux and the
            // checksum would report a difference that does not exist.
            String line = "%s|%d|%s|%s|%s|%s|%s|%s|%s\n"
                    .formatted(
                            request.idempotencyKey(),
                            transaction.offset().toSeconds(),
                            request.accountReference(),
                            request.merchantReference(),
                            request.type(),
                            request.channel(),
                            request.amount().value(),
                            request.originCountry(),
                            String.valueOf(request.deviceReference()));
            digest.update(line.getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
