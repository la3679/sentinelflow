/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.domain;

/**
 * How a transaction entered SentinelFlow.
 *
 * <p>{@code SCENARIO_REPLAY} is what tells a consumer the data is a rerun of a known scenario
 * rather than fresh synthetic traffic, so a demo cannot be mistaken for a measurement.
 */
public enum IngestionSource {
    API,
    BATCH,
    GENERATOR,
    SCENARIO_REPLAY
}
