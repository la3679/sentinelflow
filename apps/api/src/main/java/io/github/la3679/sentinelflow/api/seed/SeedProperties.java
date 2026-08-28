/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.seed;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the demo seed.
 *
 * <p><strong>Off unless asked for.</strong> Seeding writes rows, and a service that quietly
 * populates whatever database it is pointed at is a service nobody can safely point at anything.
 * The default is {@code false} in every environment, including local.
 *
 * @param enabled whether to load demo data when the application starts
 * @param seed the pseudo-random seed. The same value produces the same dataset on any machine, so
 *     it is quoted in bug reports and pinned in CI. Changing it changes every generated reference.
 * @param profile how much data to load
 * @param operatorPassword the password every seeded operator is given. <strong>Required whenever
 *     seeding is enabled, and it has no default</strong> (ADR-0012 §6): a password in this file
 *     would be the same one on every machine that ever ran the seed, and a hash committed to a
 *     migration would be worse. {@code make bootstrap} generates one into the git-ignored
 *     {@code .env}.
 * @param scenarios whether to generate transaction traffic over the seeded parties as well.
 *     {@link Boolean} rather than {@code boolean} so that "unset" is distinguishable from "off" —
 *     a primitive would default to {@code false} and quietly leave every seeded demo with no
 *     transactions in it. Defaults to on, because parties with no traffic are not a demo.
 */
@ConfigurationProperties("sentinelflow.seed")
public record SeedProperties(
        boolean enabled, long seed, SeedProfile profile, String operatorPassword, Boolean scenarios) {

    /**
     * A date, so it reads as a deliberate constant rather than as a number someone liked. Any value
     * works; what matters is that it is written down and not generated.
     */
    public static final long DEFAULT_SEED = 20_260_826L;

    public SeedProperties {
        if (profile == null) {
            profile = SeedProfile.DEMO;
        }
        if (seed == 0L) {
            seed = DEFAULT_SEED;
        }
        if (scenarios == null) {
            scenarios = Boolean.TRUE;
        }
        // Only when the seed will actually run. An application that is not
        // seeding has no operators to give a password to, and demanding one
        // would make an unrelated deployment fail for a reason that does not
        // apply to it.
        if (enabled && (operatorPassword == null || operatorPassword.isBlank())) {
            throw new IllegalArgumentException(
                    "sentinelflow.seed.operator-password is required when seeding is enabled and has no default. "
                            + "Set SENTINELFLOW_DEMO_OPERATOR_PASSWORD; `make bootstrap` generates one into .env.");
        }
    }

    /** Whether to generate traffic. Never null after construction. */
    public boolean generateScenarios() {
        return Boolean.TRUE.equals(scenarios);
    }
}
