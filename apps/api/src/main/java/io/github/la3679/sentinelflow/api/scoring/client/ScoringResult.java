/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.scoring.client;

import io.github.la3679.sentinelflow.api.scoring.payload.ScoreResponse;

/**
 * A scoring call that answered, and what it cost the caller.
 *
 * <p><strong>Two durations, deliberately.</strong> {@code response.inferenceDurationMs()} is the
 * scoring service measuring itself; {@code latencyMs} is this application measuring the whole call,
 * including the network and any retries inside the budget. {@code risk-assessed.v1} persists the
 * second, described as "measured by the caller", and the gap between the two is what tells an
 * operator whether a slow assessment was a slow model or a slow link.
 *
 * @param response what the service returned
 * @param latencyMs wall-clock milliseconds across every attempt, measured by this application
 */
public record ScoringResult(ScoreResponse response, long latencyMs) {}
