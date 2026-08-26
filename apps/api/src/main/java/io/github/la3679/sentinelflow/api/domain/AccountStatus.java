/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.domain;

/**
 * Lifecycle of a synthetic account.
 *
 * <p>{@code FROZEN} is not {@code CLOSED}: a frozen account still holds a balance and still
 * receives attempted transactions, which is exactly the case a fraud demo needs to represent.
 */
public enum AccountStatus {
    ACTIVE,
    FROZEN,
    CLOSED
}
