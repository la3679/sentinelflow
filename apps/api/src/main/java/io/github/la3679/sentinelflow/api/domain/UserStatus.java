/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.domain;

/**
 * Whether a demo user may act.
 *
 * <p>Deliberately two states. There is no locked-out, no pending-verification and no password
 * state, because SentinelFlow stores no credential to lock out or verify.
 */
public enum UserStatus {
    ACTIVE,
    DISABLED
}
