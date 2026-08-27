/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.seed;

import java.time.Instant;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.github.la3679.sentinelflow.api.seed.scenario.ScenarioLoader;

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
@Order(SeedRunner.ORDER)
public class SeedRunner implements ApplicationRunner {

    /**
     * Explicit rather than the default precedence, because the training export runs immediately
     * after this one and has nothing to label until it has finished. Two runners left on the default
     * would run in whatever order the context produced, which is not a guarantee and would fail
     * intermittently rather than consistently.
     */
    public static final int ORDER = 0;

    private final DeterministicSeedLoader loader;
    private final ScenarioLoader scenarios;
    private final SeedProperties properties;

    public SeedRunner(DeterministicSeedLoader loader, ScenarioLoader scenarios, SeedProperties properties) {
        this.loader = loader;
        this.scenarios = scenarios;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Any exception propagates and stops startup. A service that came up
        // reporting healthy over a half-written demo dataset would be worse
        // than one that refused to start.
        loader.load(properties);

        if (properties.generateScenarios()) {
            // Parties first, always: the generator writes transactions against
            // accounts and merchants and has nothing to write against until
            // they exist. Ordering it here rather than inside either loader
            // keeps each of them independently runnable.
            //
            // Instant.now() is the end of the generated window, so a demo
            // started at any hour has recent traffic. The generator itself
            // reads no clock - see ScenarioGenerator.
            scenarios.load(properties.seed(), properties.profile(), Instant.now());
        }
    }
}
