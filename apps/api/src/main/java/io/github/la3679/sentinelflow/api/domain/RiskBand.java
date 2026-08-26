/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.domain;

/**
 * The band a final score falls into, ordered least to most severe.
 *
 * <p>Banding is a policy decision with its own version: the same score can band differently under
 * a different policy, which is why every assessment records the policy version that produced it.
 */
public enum RiskBand {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
