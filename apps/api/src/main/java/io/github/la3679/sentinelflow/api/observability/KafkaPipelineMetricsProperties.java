/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.observability;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How often this application asks the broker how far behind it is, and how long it will wait.
 *
 * @param enabled whether the readings are taken at all. On everywhere there is a broker; off in the
 *     test contexts that need the schema and nothing else, for the same reason
 *     {@code sentinelflow.consumer.enabled} is — a scheduled task failing against an address that
 *     does not resolve is noise on every run of an unrelated suite.
 * @param refreshInterval the gap between readings. Matched to Prometheus's 15-second scrape by
 *     default: reading faster produces values nothing collects, and reading slower produces a
 *     sawtooth where a scrape sometimes lands on a stale figure and sometimes on a fresh one.
 * @param timeout the ceiling on one round of admin calls. Short on purpose. This runs on a
 *     scheduler thread, not on a request thread, so a slow broker cannot hurt a caller — but a call
 *     with no timeout can still pile scheduled runs on top of each other until one of them is
 *     minutes old and reporting it as current.
 */
@ConfigurationProperties("sentinelflow.observability.kafka")
public record KafkaPipelineMetricsProperties(boolean enabled, Duration refreshInterval, Duration timeout) {

    public KafkaPipelineMetricsProperties {
        refreshInterval = refreshInterval == null ? Duration.ofSeconds(15) : refreshInterval;
        timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        if (refreshInterval.isNegative() || refreshInterval.isZero()) {
            throw new IllegalArgumentException(
                    "refreshInterval must be positive; " + refreshInterval + " would read continuously");
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive; " + timeout
                    + " would abandon every read before the broker could answer");
        }
    }
}
