/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.seed.scenario;

import java.util.Map;

import io.github.la3679.sentinelflow.api.seed.SeedProfile;

/**
 * What a scenario run produced, and enough to prove another run produced the same thing.
 *
 * <p>§13.1 of the build prompt requires a manifest carrying the seed, the generator version, the
 * counts, the label distribution and a checksum. All five are here.
 *
 * <p><strong>The checksum is over the offsets and the request content, not over what was
 * written.</strong> Transaction identifiers are UUIDv7 and therefore carry the wall-clock time they
 * were minted, and {@code transaction_reference} comes from a database sequence that gaps whenever a
 * write rolls back. Either would make two identical runs produce different checksums, which would
 * make the checksum prove the opposite of what it claims. What is deterministic is the traffic the
 * generator described, and that is what is hashed.
 *
 * <p><strong>{@code labelDistribution} lives here and nowhere else.</strong> It is the one place in
 * the system that knows which transactions were planted, and it is a count rather than a mapping —
 * enough to say "this dataset contains twelve card-testing runs" without being a lookup table that
 * could be joined back to rows a model is about to be asked to score. See {@link ScenarioType}.
 *
 * @param generatorVersion the generator's own version, so a dataset can be attributed to the code
 *     that produced it after that code has changed
 * @param seed the pseudo-random seed the run used
 * @param profile the size profile the run used
 * @param generated how many transactions the generator produced
 * @param written how many were actually persisted. Lower than {@code generated} means the database
 *     already held part of this dataset and rejected the duplicates, which is the reload case
 *     working rather than a fault.
 * @param labelDistribution how many transactions belong to each planted shape, {@code NORMAL}
 *     included
 * @param checksum lower-case hex SHA-256 over every generated request in order
 * @param skipped true when the database already held generated traffic and nothing was written
 */
public record ScenarioManifest(
        String generatorVersion,
        long seed,
        SeedProfile profile,
        int generated,
        int written,
        Map<ScenarioType, Integer> labelDistribution,
        String checksum,
        boolean skipped) {

    public ScenarioManifest {
        labelDistribution = Map.copyOf(labelDistribution);
    }

    public static ScenarioManifest skipped(long seed, SeedProfile profile) {
        return new ScenarioManifest(ScenarioGenerator.GENERATOR_VERSION, seed, profile, 0, 0, Map.of(), "", true);
    }

    /** How many transactions belong to a planted shape rather than to the background. */
    public int planted() {
        return labelDistribution.entrySet().stream()
                .filter(entry -> entry.getKey() != ScenarioType.NORMAL)
                .mapToInt(Map.Entry::getValue)
                .sum();
    }
}
