/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.scoring.export;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import io.github.la3679.sentinelflow.api.persistence.entity.TransactionRecord;
import io.github.la3679.sentinelflow.api.persistence.repository.TransactionRepository;
import io.github.la3679.sentinelflow.api.risk.rules.RuleEngine;
import io.github.la3679.sentinelflow.api.risk.rules.RuleOutcome;
import io.github.la3679.sentinelflow.api.scoring.AccountContextAssembler;
import io.github.la3679.sentinelflow.api.scoring.ScoringContextProperties;
import io.github.la3679.sentinelflow.api.scoring.payload.ScoreRequest;
import io.github.la3679.sentinelflow.api.seed.SeedProfile;
import io.github.la3679.sentinelflow.api.seed.scenario.GeneratedTransaction;
import io.github.la3679.sentinelflow.api.seed.scenario.ScenarioDataset;
import io.github.la3679.sentinelflow.api.seed.scenario.ScenarioGenerator;
import io.github.la3679.sentinelflow.api.seed.scenario.ScenarioType;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes the labelled training dataset ADR-0010 §1 specifies.
 *
 * <p>One JSON object per line: the exact {@code ScoreRequest} the scoring service would receive for
 * that transaction, plus the {@link ScenarioType} the generator planted it as. Beside it, a manifest
 * recording everything needed to produce the file again.
 *
 * <h2>Why the label has to be recovered rather than read</h2>
 *
 * {@code ScenarioType} never enters the database, deliberately — a label column on
 * {@code transactions} would be information that only exists after the fact, sitting next to the row
 * a model is asked to score. So the export <em>regenerates</em> the dataset from the same seed and
 * profile and joins it to the stored rows by idempotency key, which the generator derives from the
 * seed and a sequence number rather than from a clock. That is what lets an export run days after
 * the load and still land every label on its own row.
 *
 * <p>The regeneration goes through {@link ScenarioDataset} rather than being repeated here, because
 * both halves must read the accounts and merchants in the same order or the labels shift. A model
 * trained on shifted labels is not obviously broken — it is quietly mediocre, which is far harder to
 * attribute than a crash.
 *
 * <h2>Why it calls the runtime assembler, not a copy of it</h2>
 *
 * {@link AccountContextAssembler#assemble(TransactionRecord)} is the same method the scoring client
 * will call, including its read of the account's balance. Nothing about the context is recomputed
 * for training. That is the whole of ADR-0010 §1, and it is achievable here for a reason worth
 * stating plainly: <strong>nothing in this application ever changes an account balance</strong> —
 * no service, no consumer, no migration — so an account's balance today is the balance it had
 * throughout the generated window, and "current" and "as of" are the same number.
 *
 * <p><strong>That is an invariant, not a coincidence, and this export depends on it.</strong>
 * {@code TrainingDatasetExporterIT} asserts it directly. If balance mutation is ever introduced, the
 * assertion fails, and this class must start supplying a reconstructed as-of balance through
 * {@link AccountContextAssembler#assembleContext} — which is why that seam exists. Discovering it by
 * a failing assertion is the point; discovering it by a feature that has been quietly wrong for
 * months is what the assertion prevents.
 *
 * <h2>Not an HTTP endpoint</h2>
 *
 * An explicit offline command (§12.6), for the same reason the seed is: it writes files and reads
 * the whole transaction table, and exposing that to the network would make it reachable by anyone
 * who can reach the service.
 */
@Service
public class TrainingDatasetExporter {

    private static final Logger log = LoggerFactory.getLogger(TrainingDatasetExporter.class);

    private final ScenarioDataset dataset;
    private final TransactionRepository transactions;
    private final AccountContextAssembler assembler;
    private final RuleEngine ruleEngine;
    private final ScoringContextProperties contextProperties;
    private final TrainingExportProperties properties;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;

    public TrainingDatasetExporter(
            ScenarioDataset dataset,
            TransactionRepository transactions,
            AccountContextAssembler assembler,
            RuleEngine ruleEngine,
            ScoringContextProperties contextProperties,
            TrainingExportProperties properties,
            ObjectMapper objectMapper,
            JdbcTemplate jdbc) {
        this.dataset = dataset;
        this.transactions = transactions;
        this.assembler = assembler;
        this.ruleEngine = ruleEngine;
        this.contextProperties = contextProperties;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
    }

    /**
     * A fixed instant to regenerate against.
     *
     * <p><strong>It is immaterial, and being able to say that is the point.</strong> The export
     * joins on idempotency keys, and the generator derives those from the seed, a sequence number
     * and the transaction's <em>offset within the window</em> — never from an absolute instant. So
     * any window end produces the same keys, and an export run days or weeks after the load still
     * lands every label on its own row.
     *
     * <p>Nothing regenerated is written to the file either: every timestamp in the dataset comes
     * from the stored rows, through the assembler. Taking a fixed constant rather than
     * {@code Instant.now()} keeps that visible instead of leaving a clock in a path whose whole
     * claim is reproducibility. {@code ScenarioDatasetTests} asserts two different window ends
     * produce identical keys.
     */
    private static final Instant REGENERATION_REFERENCE = Instant.parse("2026-01-01T00:00:00Z");

    /**
     * Exports the dataset for a seed and profile.
     *
     * @throws IllegalStateException if an export already exists and {@code overwrite} is off, or if
     *     the database holds no generated traffic to label
     * @throws UncheckedIOException if the dataset cannot be written
     */
    public TrainingExportManifest export(long seed, SeedProfile profile) {
        Path directory = properties.directory();
        Path datasetFile = properties.datasetFile();
        if (!properties.overwrite() && Files.exists(datasetFile)) {
            throw new IllegalStateException("An export already exists at " + datasetFile.toAbsolutePath()
                    + ". Exports are reproducible, so rewriting one is either pointless or the loss of a "
                    + "dataset whose metrics have been published. Set "
                    + "SENTINELFLOW_SCORING_EXPORT_OVERWRITE=true if that is what you mean.");
        }

        List<GeneratedTransaction> generated = dataset.generate(seed, profile, REGENERATION_REFERENCE);
        Map<String, UUID> accountIds = accountIdsByReference();

        // Read once and recorded in the manifest. Every example in one file is
        // scored by one ruleset, and a dataset whose rule scores came from two
        // of them would compare a model against a baseline that never existed.
        String rulesetVersion = null;

        Map<ScenarioType, Integer> distribution = new EnumMap<>(ScenarioType.class);
        int exported = 0;
        int unmatched = 0;

        MessageDigest digest = sha256();
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create " + directory.toAbsolutePath(), e);
        }

        try (OutputStream file = Files.newOutputStream(
                        datasetFile,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING);
                DigestOutputStream digested = new DigestOutputStream(file, digest);
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(digested, StandardCharsets.UTF_8))) {

            for (GeneratedTransaction candidate : generated) {
                Optional<TransactionRecord> stored = find(candidate, accountIds);
                if (stored.isEmpty()) {
                    unmatched++;
                    continue;
                }

                // The shipped engine, on the shipped request. Not a copy of the
                // rules for training: ADR-0010 section 5's margin over the
                // baseline only means something if the baseline is what the API
                // runs when scoring is unavailable.
                ScoreRequest request = assembler.assemble(stored.get());
                RuleOutcome rules = ruleEngine.evaluate(request);
                rulesetVersion = rules.rulesetVersion();
                TrainingExample example = TrainingExample.of(request, candidate.scenario(), rules);
                writer.write(objectMapper.writeValueAsString(example));
                // \n, never the platform separator: the dataset's checksum is
                // over these bytes, and the same data must not fingerprint
                // differently on Windows and Linux.
                writer.write('\n');

                distribution.merge(candidate.scenario(), 1, Integer::sum);
                exported++;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + datasetFile.toAbsolutePath(), e);
        }

        if (exported == 0) {
            throw new IllegalStateException("Generated " + generated.size()
                    + " transactions and matched none of them in the database. Load them first: make seed");
        }
        if (unmatched > 0) {
            // Reported rather than rounded off. It means the database and the
            // generator disagree - a different seed, a partial load, or rows
            // deleted - and a dataset smaller than its manifest claims is
            // exactly the kind of quiet difference that makes a metric
            // irreproducible later.
            log.warn(
                    "{} generated transactions had no stored row and were skipped. The database and this "
                            + "seed/profile do not describe the same dataset.",
                    unmatched);
        }

        TrainingExportManifest manifest = new TrainingExportManifest(
                ScenarioGenerator.GENERATOR_VERSION,
                seed,
                profile.name(),
                Instant.now(),
                AccountContextAssembler.CONTEXT_VERSION,
                contextProperties.lookbackWindow().toSeconds(),
                exported,
                generated.size(),
                distribution,
                TrainingExportManifest.NEGATIVE_LABEL,
                rulesetVersion,
                ScenarioDataset.checksumOf(generated),
                HexFormat.of().formatHex(digest.digest()));

        writeManifest(manifest);

        log.info(
                "Training export complete: {} examples ({} planted) to {}, dataset sha256 {}",
                manifest.exported(),
                manifest.planted(),
                datasetFile.toAbsolutePath(),
                manifest.datasetSha256());
        return manifest;
    }

    private Optional<TransactionRecord> find(GeneratedTransaction candidate, Map<String, UUID> accountIds) {
        UUID accountId = accountIds.get(candidate.request().accountReference());
        if (accountId == null) {
            return Optional.empty();
        }
        return transactions.findByAccountIdAndIdempotencyKey(
                accountId, candidate.request().idempotencyKey());
    }

    /**
     * Every account reference to its identifier, in one query.
     *
     * <p>A lookup per generated transaction would be tens of thousands of round trips to answer a
     * question with a few hundred distinct answers.
     */
    private Map<String, UUID> accountIdsByReference() {
        Map<String, UUID> byReference = new HashMap<>();
        jdbc.query("SELECT id, account_reference FROM accounts", rs -> {
            byReference.put(rs.getString("account_reference"), rs.getObject("id", UUID.class));
        });
        return byReference;
    }

    private void writeManifest(TrainingExportManifest manifest) {
        Path path = properties.manifestFile();
        try {
            Files.writeString(
                    path,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest) + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + path.toAbsolutePath(), e);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }
}
