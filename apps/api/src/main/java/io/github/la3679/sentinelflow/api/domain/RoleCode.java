/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.domain;

/**
 * The fixed set of principal roles.
 *
 * <p>Reference data: the four rows in {@code roles} exist because these four constants do, and a
 * fifth role means a migration, not a runtime insert.
 */
public enum RoleCode {
    ANALYST,
    ADMINISTRATOR,
    AUDITOR,
    SYSTEM
}
