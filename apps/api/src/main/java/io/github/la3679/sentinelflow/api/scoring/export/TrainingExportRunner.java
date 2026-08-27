/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.scoring.export;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.github.la3679.sentinelflow.api.seed.SeedProperties;
import io.github.la3679.sentinelflow.api.seed.SeedRunner;

/**
 * Runs the training export at startup, and only when asked.
 *
 * <p>{@code havingValue = "true"} with no {@code matchIfMissing}, so the bean does not exist unless
 * {@code sentinelflow.scoring.export.enabled} is explicitly set — the same control the seed runner
 * uses, for the same reason: this writes files and reads the whole transaction table.
 *
 * <p><strong>Ordered after the seed.</strong> Both can be enabled in one run, and the export has
 * nothing to label until the scenario load has written it. Ordering it here rather than making the
 * exporter wait keeps each of them independently runnable, and the exporter still fails with a
 * usable message if it is pointed at an empty database.
 *
 * <p><strong>It reads its seed and profile from {@code sentinelflow.seed}</strong> rather than
 * carrying its own. The export exists to label the traffic that seed produced; two independent
 * settings would let them disagree, and the symptom would be an export that matched nothing — or,
 * far worse, one that matched a different dataset's rows.
 *
 * <p>Startup is the right hook rather than an HTTP endpoint: this is an operator action taken
 * against a database, and exposing it as a route would put a full table scan and a file write
 * behind whatever can reach the service.
 */
@Component
@ConditionalOnProperty(prefix = "sentinelflow.scoring.export", name = "enabled", havingValue = "true")
@Order(TrainingExportRunner.AFTER_THE_SEED)
public class TrainingExportRunner implements ApplicationRunner {

    /**
     * Lower runs first. {@code SeedRunner} declares {@code SeedRunner.ORDER}, and this is one step
     * after it — explicitly, because two beans left on the default precedence run in whatever order
     * the context happens to produce, which is not a guarantee and would fail intermittently.
     */
    static final int AFTER_THE_SEED = SeedRunner.ORDER + 1;

    private final TrainingDatasetExporter exporter;
    private final SeedProperties seed;

    public TrainingExportRunner(TrainingDatasetExporter exporter, SeedProperties seed) {
        this.exporter = exporter;
        this.seed = seed;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Any exception propagates and stops startup, matching the seed runner.
        // A service reporting healthy over a half-written training set would be
        // worse than one that refused to start, because the half-written set is
        // what someone would go on to train against.
        exporter.export(seed.seed(), seed.profile());
    }
}
