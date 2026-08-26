/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.messaging.consumer;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How a consumer behaves when handling fails. The policy is ADR-0006 §4.
 *
 * <p>Deliberately separate from {@code sentinelflow.outbox}, whose retry values look identical and
 * mean something different. The relay's budget is "how long to wait for a broker to come back",
 * spent against one row in a database. A consumer's is "how long to block this partition", spent
 * against every record queued behind the one that is failing — so the two are tuned against
 * different costs and a shared block would couple them by accident.
 *
 * @param enabled whether the listener runs at all. On everywhere it has a broker; off in the many
 *     test contexts that need the schema and nothing else, because a listener with no resolvable
 *     broker fails the whole application context at startup rather than degrading.
 * @param retryBase the first backoff interval, doubled per attempt
 * @param retryMaxDelay the ceiling the doubling stops at. Kept short: a consumer's retry blocks the
 *     partition, so a five-minute ceiling here would stall everything behind one record.
 * @param maxAttempts total attempts, including the first. One means no retry at all.
 */
@ConfigurationProperties("sentinelflow.consumer")
public record ConsumerProperties(boolean enabled, Duration retryBase, Duration retryMaxDelay, int maxAttempts) {

    public ConsumerProperties {
        retryBase = retryBase == null ? Duration.ofMillis(500) : retryBase;
        retryMaxDelay = retryMaxDelay == null ? Duration.ofSeconds(20) : retryMaxDelay;
        maxAttempts = maxAttempts <= 0 ? 5 : maxAttempts;
    }
}
