/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.seed;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Runs the seed at startup, and only when asked.
 *
 * <p>{@code havingValue = "true"} with no {@code matchIfMissing}, so the bean does not exist unless
 * {@code sentinelflow.seed.enabled} is explicitly set. A service that populates whatever database it
 * is pointed at is a service nobody can safely point at anything, and "we will remember to turn it
 * off" is not a control.
 *
 * <p>Startup is the right hook rather than an HTTP endpoint: seeding is an operator action taken
 * before the service carries traffic, and exposing it as a route would make destructive-adjacent
 * behaviour reachable from the network for the sake of convenience nobody needs.
 */
@Component
@ConditionalOnProperty(prefix = "sentinelflow.seed", name = "enabled", havingValue = "true")
public class SeedRunner implements ApplicationRunner {

    private final DeterministicSeedLoader loader;
    private final SeedProperties properties;

    public SeedRunner(DeterministicSeedLoader loader, SeedProperties properties) {
        this.loader = loader;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Any exception propagates and stops startup. A service that came up
        // reporting healthy over a half-written demo dataset would be worse
        // than one that refused to start.
        loader.load(properties);
    }
}
