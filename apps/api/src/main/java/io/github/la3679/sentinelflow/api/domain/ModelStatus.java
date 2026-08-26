/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.domain;

/**
 * Where a registered model sits in its lifecycle.
 *
 * <p>At most one model is {@code ACTIVE} at a time, and the database enforces that rather than
 * trusting whichever code path last performed a promotion.
 */
public enum ModelStatus {
    CANDIDATE,
    ACTIVE,
    RETIRED
}
