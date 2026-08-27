/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.seed.scenario;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import io.github.la3679.sentinelflow.api.domain.IngestionSource;
import io.github.la3679.sentinelflow.api.seed.SeedProfile;
import io.github.la3679.sentinelflow.api.service.TransactionWriter;

/**
 * Writes generated traffic into the system the way the system expects to receive it.
 *
 * <p><strong>Through {@link TransactionWriter}, not through SQL.</strong> That is the whole design
 * decision here. The writer is what ingestion uses, so generated data gets the same validation, the
 * same reference resolution, the same idempotency constraint, and — the part that matters — the same
 * outbox row. Traffic loaded this way flows through the relay and the consumer exactly as a posted
 * transaction does, which is what makes a seeded demo a demonstration of the pipeline rather than a
 * table full of rows the pipeline never saw.
 *
 * <p>A private insert path would be faster and would prove nothing. It would also be able to write
 * rows that ingestion would have rejected, and the first symptom of that is a demo that behaves
 * differently from the product.
 *
 * <p><strong>{@code ingestion_source} is {@code GENERATOR}</strong>, so generated traffic is
 * distinguishable from anything a client posted, for ever, in the row itself.
 *
 * <p><strong>Idempotent, and the database is what makes it so.</strong> The generator derives every
 * idempotency key from the seed and a sequence number, so a second run produces the same keys and
 * {@code transactions_idempotency_unique} rejects each of them. This class also short-circuits when
 * generated traffic is already present, which turns a no-op into a fast no-op rather than into a
 * guarantee — the guarantee is the constraint.
 */
@Service
public class ScenarioLoader {

    private static final Logger log = LoggerFactory.getLogger(ScenarioLoader.class);

    private final TransactionWriter writer;
    private final JdbcTemplate jdbc;
    private final ScenarioDataset dataset;

    public ScenarioLoader(TransactionWriter writer, JdbcTemplate jdbc, ScenarioDataset dataset) {
        this.writer = writer;
        this.jdbc = jdbc;
        this.dataset = dataset;
    }

    /**
     * Generates and loads a dataset over whatever parties are already present.
     *
     * <p>Not {@code @Transactional}. Each transaction is written in its own — which is what
     * {@link TransactionWriter#write} opens — so a duplicate part-way through does not roll back
     * everything before it, and a large profile does not hold one transaction open for its whole
     * run. That also means a partial load is possible; the manifest reports what was written rather
     * than what was asked for, so a partial load is visible rather than silent.
     *
     * @param now the end of the generated window. Traffic is placed in the fourteen days before it,
     *     so a demo started at any time has recent activity.
     */
    public ScenarioManifest load(long seed, SeedProfile profile, Instant now) {
        if (alreadyGenerated()) {
            log.info("Generated traffic is already present; scenario load skipped.");
            return ScenarioManifest.skipped(seed, profile);
        }

        // Regenerated through ScenarioDataset rather than here, so the labelled
        // training export recovers exactly this list later - the labels never
        // enter the schema, so the export has nothing else to join them by.
        List<GeneratedTransaction> generated = dataset.generate(seed, profile, now);

        int written = 0;
        Map<ScenarioType, Integer> distribution = new EnumMap<>(ScenarioType.class);
        for (GeneratedTransaction transaction : generated) {
            distribution.merge(transaction.scenario(), 1, Integer::sum);
            if (write(transaction)) {
                written++;
            }
        }

        ScenarioManifest manifest = new ScenarioManifest(
                ScenarioGenerator.GENERATOR_VERSION,
                seed,
                profile,
                generated.size(),
                written,
                distribution,
                ScenarioDataset.checksumOf(generated),
                false);

        log.info(
                "Scenario load complete: {} generated, {} written, {} planted, checksum {}",
                manifest.generated(),
                manifest.written(),
                manifest.planted(),
                manifest.checksum());
        return manifest;
    }

    /**
     * @return true if the transaction was written, false if the database already had it
     */
    private boolean write(GeneratedTransaction transaction) {
        try {
            // A correlation id per transaction, not per run. It ties this row,
            // its outbox event, and everything the consumer logs about it into
            // one traceable line of work - which is exactly what it does for a
            // posted transaction, and what makes generated traffic debuggable
            // the same way.
            writer.write(transaction.request(), UUID.randomUUID(), IngestionSource.GENERATOR);
            return true;
        } catch (DataIntegrityViolationException duplicate) {
            // The reload case. Expected, and not worth a warning: the keys are
            // deterministic precisely so that running this twice is harmless.
            log.debug(
                    "Generated transaction {} was already present",
                    transaction.request().idempotencyKey());
            return false;
        }
    }

    /**
     * Whether this database already holds generated traffic.
     *
     * <p>Reads {@code ingestion_source} rather than counting transactions, so a database holding
     * only posted traffic is still seeded. {@code EXISTS} rather than a count: the answer is a
     * boolean and a count over a large table to produce one is waste.
     */
    private boolean alreadyGenerated() {
        Boolean present = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM transactions WHERE ingestion_source = 'GENERATOR')", Boolean.class);
        return Boolean.TRUE.equals(present);
    }
}
