/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.scoring.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import io.github.la3679.sentinelflow.api.persistence.repository.TransactionRepository;
import io.github.la3679.sentinelflow.api.scoring.AccountContextAssembler;
import io.github.la3679.sentinelflow.api.scoring.ScoringContextProperties;
import io.github.la3679.sentinelflow.api.seed.SeedProfile;
import io.github.la3679.sentinelflow.api.seed.scenario.GeneratedTransaction;
import io.github.la3679.sentinelflow.api.seed.scenario.ScenarioDataset;
import io.github.la3679.sentinelflow.api.seed.scenario.ScenarioLoader;
import io.github.la3679.sentinelflow.api.seed.scenario.ScenarioType;
import io.github.la3679.sentinelflow.api.support.AbstractPostgresTest;
import io.github.la3679.sentinelflow.api.support.SchemaFixtures;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The labelled export, end to end, against real PostgreSQL.
 *
 * <p>What is worth proving here is not that a file appears. It is that <strong>every line carries
 * its own label</strong>. The export recovers labels by regenerating the dataset and joining to the
 * stored rows on idempotency key, and a join that shifted by one would still produce a complete,
 * well-formed, entirely mislabelled file. Nothing downstream would notice: the trainer would run,
 * the metrics would compute, and the model would simply be mediocre for a reason nobody could
 * attribute.
 *
 * <p>{@link #labelsLandOnTheRightRows()} therefore re-derives the join through a different path
 * from the one the exporter used — line, to {@code transactionId}, to the stored row, to its
 * idempotency key — and compares. Two independent routes to the same answer is the only check that
 * a shifted join fails.
 *
 * <p>The relay is disabled, as in {@code ScenarioLoaderIT}: otherwise a scheduled thread drains the
 * outbox while these assertions run and the suite passes or fails on timing.
 */
@TestPropertySource(properties = "sentinelflow.outbox.enabled=false")
class TrainingDatasetExporterIT extends AbstractPostgresTest {

    private static final long SEED = 20_260_826L;
    private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");
    private static final BigDecimal SEEDED_BALANCE = new BigDecimal("1500.0000");

    @TempDir
    static Path exportDirectory;

    @DynamicPropertySource
    static void exportLocation(DynamicPropertyRegistry registry) {
        // A temporary directory rather than the configured default, so a test
        // run never writes into the developer's working tree.
        registry.add("sentinelflow.scoring.export.directory", () -> exportDirectory.toString());
        registry.add("sentinelflow.scoring.export.overwrite", () -> true);
    }

    @Autowired
    private TrainingDatasetExporter exporter;

    @Autowired
    private ScenarioLoader loader;

    @Autowired
    private ScenarioDataset scenarios;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private AccountContextAssembler assembler;

    @Autowired
    private ScoringContextProperties contextProperties;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private SchemaFixtures fixtures;

    @BeforeEach
    void reset() throws IOException {
        jdbc.execute("TRUNCATE transactions, outbox_events CASCADE");
        jdbc.execute("TRUNCATE accounts, merchants, customers CASCADE");
        fixtures = new SchemaFixtures(jdbc);
        Files.deleteIfExists(datasetFile());
        Files.deleteIfExists(manifestFile());
    }

    // ----------------------------------------------------------------------- //
    // The thing that would otherwise fail silently
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("each line carries the label of its own transaction, re-derived by a different route")
    void labelsLandOnTheRightRows() {
        seedParties(6, 5);
        loader.load(SEED, SeedProfile.CI, NOW);
        exporter.export(SEED, SeedProfile.CI);

        // Route one, as the exporter took it: regenerate, key to label.
        Map<String, ScenarioType> labelByKey = new HashMap<>();
        for (GeneratedTransaction generated : scenarios.generate(SEED, SeedProfile.CI, NOW)) {
            labelByKey.put(generated.request().idempotencyKey(), generated.scenario());
        }

        // Route two, backwards from the file: line, to transactionId, to the
        // stored row, to its key. If the export's join slipped, these disagree.
        List<JsonNode> lines = lines();
        assertThat(lines).isNotEmpty();

        for (JsonNode line : lines) {
            UUID id =
                    UUID.fromString(line.get("transaction").get("transactionId").asString());
            String key = jdbc.queryForObject("SELECT idempotency_key FROM transactions WHERE id = ?", String.class, id);

            assertThat(line.get("label").asString())
                    .as("transaction %s stores key %s, which the generator planted as %s", id, key, labelByKey.get(key))
                    .isEqualTo(labelByKey.get(key).name());
        }
    }

    @Test
    @DisplayName("every off-hours example really is in the small hours, so the label describes the data")
    void plantedShapesLookLikeTheirLabels() {
        seedParties(6, 5);
        loader.load(SEED, SeedProfile.CI, NOW);
        exporter.export(SEED, SeedProfile.CI);

        List<JsonNode> offHours = lines().stream()
                .filter(line -> ScenarioType.OFF_HOURS_NEW_DEVICE
                        .name()
                        .equals(line.get("label").asString()))
                .toList();

        assertThat(offHours)
                .as("the shape has to exist in the CI profile or there is nothing to check")
                .isNotEmpty();
        assertThat(offHours)
                .as("a label is only worth training on if the data under it matches — this is the "
                        + "assertion the generator's off-hours defect would have failed")
                .allSatisfy(line -> assertThat(hourOf(line)).isBetween(2, 3));
    }

    // ----------------------------------------------------------------------- //
    // Shape and completeness
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("every stored transaction becomes one labelled line")
    void exportsOneLinePerTransaction() {
        seedParties(6, 5);
        long written = loader.load(SEED, SeedProfile.CI, NOW).written();

        TrainingExportManifest manifest = exporter.export(SEED, SeedProfile.CI);

        assertThat(manifest.exported())
                .as("a generated transaction with no line is a labelled row silently dropped")
                .isEqualTo((int) written);
        assertThat(lines()).hasSize(manifest.exported());
    }

    @Test
    @DisplayName("a line is a ScoreRequest plus a label, and nothing else")
    void linesAreScoreRequestsPlusALabel() {
        seedParties(6, 5);
        loader.load(SEED, SeedProfile.CI, NOW);
        exporter.export(SEED, SeedProfile.CI);

        JsonNode line = lines().getFirst();

        assertThat(fieldNames(line))
                .as("the training record is the served object plus a label, so a model is trained "
                        + "on what it will be given (ADR-0010 section 1)")
                .containsExactlyInAnyOrder("transaction", "accountContext", "label");
        assertThat(fieldNames(line.get("transaction")))
                .containsExactlyInAnyOrder(
                        "transactionId",
                        "accountReference",
                        "merchantReference",
                        "merchantCategoryCode",
                        "type",
                        "channel",
                        "amount",
                        "originCountry",
                        "deviceReference",
                        "occurredAt");
        assertThat(fieldNames(line.get("accountContext")))
                .containsExactlyInAnyOrder(
                        "contextVersion",
                        "lookbackWindowSeconds",
                        "accountOpenedAt",
                        "currentBalance",
                        "recentTransactions",
                        "truncated");
    }

    @Test
    @DisplayName("money is a decimal string and a null device is present rather than omitted")
    void honoursTheContractsAwkwardCases() {
        seedParties(6, 5);
        loader.load(SEED, SeedProfile.CI, NOW);
        exporter.export(SEED, SeedProfile.CI);

        assertThat(lines()).allSatisfy(line -> {
            assertThat(line.get("transaction").get("amount").get("value").isString())
                    .as("a JSON number would be rounded by every JavaScript consumer before "
                            + "application code saw it (ADR-0007)")
                    .isTrue();
            assertThat(line.get("transaction").has("deviceReference"))
                    .as("required and nullable: the service must never have to distinguish "
                            + "\"this channel has no device\" from \"the field was omitted\"")
                    .isTrue();
        });
    }

    @Test
    @DisplayName("no line carries anything that is only known after the fact")
    void carriesNoPostHocField() {
        seedParties(6, 5);
        loader.load(SEED, SeedProfile.CI, NOW);
        exporter.export(SEED, SeedProfile.CI);

        assertThat(lines()).allSatisfy(line -> assertThat(fieldNames(line.get("transaction")))
                .as("a field that exists only after the pipeline or an analyst has acted is the "
                        + "textbook leak, and the cheapest place to stop it is the wire")
                .doesNotContain("processingStatus", "ingestedAt", "ingestionSource", "riskAssessment", "alert"));
    }

    @Test
    @DisplayName("no example carries account history from at or after its own transaction")
    void carriesNoFutureHistory() {
        seedParties(6, 5);
        loader.load(SEED, SeedProfile.CI, NOW);
        exporter.export(SEED, SeedProfile.CI);

        assertThat(lines()).allSatisfy(line -> {
            Instant at = Instant.parse(line.get("transaction").get("occurredAt").asString());
            for (JsonNode recent : line.get("accountContext").get("recentTransactions")) {
                assertThat(Instant.parse(recent.get("occurredAt").asString()))
                        .as("leakage is what makes every metric look better; the export inherits the "
                                + "assembler's strict window and this asserts it survived the trip")
                        .isBefore(at);
            }
        });
    }

    // ----------------------------------------------------------------------- //
    // The manifest, and reproducibility
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("the manifest describes the file that was written")
    void manifestDescribesTheDataset() throws IOException {
        seedParties(6, 5);
        loader.load(SEED, SeedProfile.CI, NOW);

        TrainingExportManifest manifest = exporter.export(SEED, SeedProfile.CI);
        JsonNode written = objectMapper.readTree(Files.readString(manifestFile(), StandardCharsets.UTF_8));

        assertThat(written.get("seed").asLong()).isEqualTo(SEED);
        assertThat(written.get("profile").asString()).isEqualTo("CI");
        assertThat(written.get("generatorVersion").asString())
                .as("a shape redefined under an unchanged seed produces different data from the "
                        + "same number, so the seed alone does not identify a dataset")
                .isNotBlank();
        assertThat(written.get("negativeLabel").asString())
                .as("stated here so NORMAL is not a magic string in two languages")
                .isEqualTo(ScenarioType.NORMAL.name());
        assertThat(written.get("lookbackWindowSeconds").asLong())
                .isEqualTo(contextProperties.lookbackWindow().toSeconds());
        assertThat(written.get("exported").asInt()).isEqualTo(manifest.exported());

        int fromDistribution = manifest.distribution().values().stream()
                .mapToInt(Integer::intValue)
                .sum();
        assertThat(fromDistribution)
                .as("the class balance has to add up to the dataset, or every metric computed "
                        + "against it is describing something else")
                .isEqualTo(manifest.exported());
        assertThat(manifest.planted())
                .as("a dataset with no positives trains a model that answers no to everything")
                .isPositive();
    }

    @Test
    @DisplayName("the dataset checksum is over the bytes actually written")
    void checksumCoversTheFile() throws Exception {
        seedParties(6, 5);
        loader.load(SEED, SeedProfile.CI, NOW);

        TrainingExportManifest manifest = exporter.export(SEED, SeedProfile.CI);

        String recomputed = HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(datasetFile())));

        assertThat(manifest.datasetSha256())
                .as("this is the fingerprint a rerun is compared against, so it has to be of the "
                        + "file rather than of what the exporter believed it wrote")
                .isEqualTo(recomputed);
    }

    @Test
    @DisplayName("exporting twice over unchanged data produces byte-identical output")
    void isReproducible() throws IOException {
        seedParties(6, 5);
        loader.load(SEED, SeedProfile.CI, NOW);

        TrainingExportManifest first = exporter.export(SEED, SeedProfile.CI);
        byte[] firstBytes = Files.readAllBytes(datasetFile());
        TrainingExportManifest second = exporter.export(SEED, SeedProfile.CI);
        byte[] secondBytes = Files.readAllBytes(datasetFile());

        assertThat(secondBytes)
                .as("ADR-0010 section 1 says the dataset is regenerated rather than committed, "
                        + "which is only honest if regenerating produces the same file")
                .isEqualTo(firstBytes);
        assertThat(second.datasetSha256()).isEqualTo(first.datasetSha256());
    }

    // ----------------------------------------------------------------------- //
    // The invariant this export rests on
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName(
            "nothing in this application moves an account balance, which is what lets the export use the current one")
    void balancesNeverMove() {
        seedParties(6, 5);
        loader.load(SEED, SeedProfile.CI, NOW);

        List<BigDecimal> balances = jdbc.queryForList("SELECT balance FROM accounts", BigDecimal.class);

        assertThat(balances)
                .as("TrainingDatasetExporter calls the runtime assembler, balance read included, and "
                        + "that is only equivalent to an as-of balance while balances are immutable. "
                        + "If this fails, the export must start supplying a reconstructed balance "
                        + "through assembleContext — the seam exists for exactly this")
                .isNotEmpty()
                .allSatisfy(balance -> assertThat(balance).isEqualByComparingTo(SEEDED_BALANCE));
    }

    // ----------------------------------------------------------------------- //
    // Refusals
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("refusing to overwrite is the default, and it says how to mean it")
    void refusesToOverwriteByDefault() {
        seedParties(6, 5);
        loader.load(SEED, SeedProfile.CI, NOW);
        exporter.export(SEED, SeedProfile.CI);

        TrainingDatasetExporter cautious = new TrainingDatasetExporter(
                scenarios,
                transactions,
                assembler,
                contextProperties,
                new TrainingExportProperties(true, exportDirectory, false),
                objectMapper,
                jdbc);

        assertThatThrownBy(() -> cautious.export(SEED, SeedProfile.CI))
                .as("an export is reproducible, so rewriting one is either pointless work or the "
                        + "loss of a dataset whose metrics have already been published")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists")
                .hasMessageContaining("SENTINELFLOW_SCORING_EXPORT_OVERWRITE");
    }

    @Test
    @DisplayName("nothing to label fails with the command that fixes it rather than an empty file")
    void failsUsefullyWithNothingToLabel() {
        seedParties(6, 5);

        assertThatThrownBy(() -> exporter.export(SEED, SeedProfile.CI))
                .as("an empty dataset that trains successfully is worse than a failure, because "
                        + "the metrics it produces look like results")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("make seed");
    }

    @Test
    @DisplayName("no parties at all fails with the seed command rather than an obscure generator error")
    void failsUsefullyWithNoParties() {
        assertThatThrownBy(() -> exporter.export(SEED, SeedProfile.CI))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SENTINELFLOW_SEED_ENABLED");
    }

    // ----------------------------------------------------------------------- //

    private Path datasetFile() {
        return exportDirectory.resolve("dataset.jsonl");
    }

    private Path manifestFile() {
        return exportDirectory.resolve("manifest.json");
    }

    private List<JsonNode> lines() {
        try {
            return Files.readAllLines(datasetFile(), StandardCharsets.UTF_8).stream()
                    .filter(line -> !line.isBlank())
                    .map(objectMapper::readTree)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + datasetFile(), e);
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        return node.propertyNames().stream().toList();
    }

    private static int hourOf(JsonNode line) {
        return Instant.parse(line.get("transaction").get("occurredAt").asString())
                .atZone(ZoneOffset.UTC)
                .getHour();
    }

    /**
     * Enough parties for the CI profile, with fixed references.
     *
     * <p>Fixed rather than {@code SchemaFixtures}' generated ones for the reason
     * {@code ScenarioLoaderIT} records: the generator's output depends on the references it is
     * given, so reproducibility can only be asserted when the input is the same both times.
     *
     * <p>Accounts are opened well before the window so {@code accountAgeDays} is a real number
     * rather than a negative one.
     */
    private void seedParties(int accounts, int merchants) {
        UUID customer = fixtures.insertCustomer();
        for (int i = 0; i < accounts; i++) {
            jdbc.update(
                    """
                    INSERT INTO accounts (customer_id, account_reference, currency, balance, status, opened_at)
                    VALUES (?, ?, 'GBP', 1500.0000, 'ACTIVE', ?)
                    """, customer, "ACC-%06d".formatted(900_000 + i), Timestamp.from(NOW.minus(Duration.ofDays(400))));
        }
        for (int i = 0; i < merchants; i++) {
            jdbc.update("""
                    INSERT INTO merchants (merchant_reference, name, category_code, country_code)
                    VALUES (?, 'Synthetic Supplies', '5411', 'GB')
                    """, "MER-%04d".formatted(9_000 + i));
        }
    }
}
