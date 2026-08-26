/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.messaging;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the outbox relay behaves. Every value here is a decision from ADR-0005.
 *
 * @param enabled whether the relay runs at all. Off in a test that drives the drain directly, so a
 *     scheduled thread cannot publish a row out from under an assertion.
 * @param pollInterval how often the relay looks for due events. This <em>is</em> the publication
 *     delay, which is the honest cost of the outbox: nothing in this pipeline is real-time.
 * @param batchSize how many events one drain claims. Bounds how long row locks are held when the
 *     broker is slow, because the claim and the publish are the same transaction.
 * @param retryBase the first backoff interval, doubled per attempt
 * @param retryMaxDelay the ceiling the doubling stops at
 * @param maxAttempts how many failures before an event is given up on and marked {@code FAILED}
 */
@ConfigurationProperties("sentinelflow.outbox")
public record OutboxProperties(
        boolean enabled,
        Duration pollInterval,
        int batchSize,
        Duration retryBase,
        Duration retryMaxDelay,
        int maxAttempts) {

    public OutboxProperties {
        pollInterval = pollInterval == null ? Duration.ofMillis(500) : pollInterval;
        retryBase = retryBase == null ? Duration.ofSeconds(1) : retryBase;
        retryMaxDelay = retryMaxDelay == null ? Duration.ofMinutes(5) : retryMaxDelay;
        batchSize = batchSize <= 0 ? 100 : batchSize;
        maxAttempts = maxAttempts <= 0 ? 10 : maxAttempts;
    }
}
