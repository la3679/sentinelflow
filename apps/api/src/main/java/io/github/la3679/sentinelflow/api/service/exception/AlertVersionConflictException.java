/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.service.exception;

import java.util.UUID;

/**
 * Somebody else changed this alert since the caller read it.
 *
 * <p>Two analysts opening the same alert and both acting is the ordinary case in a shared queue,
 * not an edge case. The loser of that race has to be told, because the alternative is that one
 * analyst's disposition silently replaces another's and neither of them knows.
 *
 * <p>Both versions are carried so the response can say the alert moved on, and the client can
 * re-read and decide again. The version is an opaque concurrency token: a client echoes it back and
 * must never read meaning into its magnitude.
 */
public class AlertVersionConflictException extends RuntimeException {

    private final UUID alertId;
    private final long expectedVersion;
    private final Long actualVersion;

    public AlertVersionConflictException(UUID alertId, long expectedVersion, Long actualVersion) {
        super("Alert " + alertId + " was expected at version " + expectedVersion
                + (actualVersion == null ? " and has since changed" : " and is at version " + actualVersion));
        this.alertId = alertId;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public UUID alertId() {
        return alertId;
    }

    public long expectedVersion() {
        return expectedVersion;
    }

    /** The version the alert is actually at, or null when the race was caught at flush rather than by the check. */
    public Long actualVersion() {
        return actualVersion;
    }
}
