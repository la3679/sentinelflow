/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.seed;

/**
 * What a seed run produced, and enough to prove another run produced the same thing.
 *
 * <p>§13.1 of the build prompt requires a manifest carrying the seed, the generator version, the
 * counts and a checksum. The checksum is over the generated business references in generation
 * order, not over database identifiers: identifiers are UUIDv7 and therefore carry the wall-clock
 * time they were minted, so two identical runs would produce different keys and a checksum over
 * them would prove nothing. The references are the deterministic part, and they are what a
 * reproduction claim is actually about.
 *
 * @param generatorVersion the loader's own version, so a dataset can be attributed to the code that
 *     made it after that code has changed
 * @param seed the pseudo-random seed the run used
 * @param profile the size profile the run used
 * @param customers number of customers written
 * @param merchants number of merchants written
 * @param accounts number of accounts written
 * @param analysts number of demo analyst users written
 * @param checksum lower-case hex SHA-256 over every generated reference, in generation order
 * @param skipped true when the database already held demo data and nothing was written
 */
public record SeedManifest(
        String generatorVersion,
        long seed,
        SeedProfile profile,
        int customers,
        int merchants,
        int accounts,
        int analysts,
        String checksum,
        boolean skipped) {

    /** Bump when a change to the loader would produce a different dataset from the same seed. */
    public static final String GENERATOR_VERSION = "1.0.0";

    public static SeedManifest skipped(long seed, SeedProfile profile) {
        return new SeedManifest(GENERATOR_VERSION, seed, profile, 0, 0, 0, 0, "", true);
    }

    public int totalRows() {
        return customers + merchants + accounts + analysts;
    }
}
