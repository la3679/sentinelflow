/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.scoring.export;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the labelled training export is written, and whether it runs at all.
 *
 * @param enabled off unless explicitly set, like the seed and for the same reason: an application
 *     that writes files wherever it is pointed is an application nobody can safely point anywhere.
 *     There is no {@code matchIfMissing}, so the runner bean does not exist unless someone asked.
 * @param directory where {@code dataset.jsonl} and {@code manifest.json} are written. The default
 *     sits under {@code data/generated/}, which {@code .gitignore} already excludes — ADR-0010 §1
 *     says the dataset is regenerated from a recorded seed rather than committed, and the surest
 *     way to honour that is to write it somewhere Git will not offer to add.
 * @param overwrite whether to replace an existing export. Off by default: an export is
 *     reproducible, so silently rewriting one is at best pointless work and at worst the loss of a
 *     dataset whose metrics someone has already published.
 */
@ConfigurationProperties("sentinelflow.scoring.export")
public record TrainingExportProperties(boolean enabled, Path directory, boolean overwrite) {

    /** Relative to the process working directory, so the same value works in a container. */
    public static final Path DEFAULT_DIRECTORY = Path.of("data", "generated", "training");

    public TrainingExportProperties {
        directory = directory == null ? DEFAULT_DIRECTORY : directory;
    }

    /** The dataset itself: one JSON object per line, one line per labelled transaction. */
    public Path datasetFile() {
        return directory.resolve("dataset.jsonl");
    }

    /** What produced the dataset, and what it contains. */
    public Path manifestFile() {
        return directory.resolve("manifest.json");
    }
}
