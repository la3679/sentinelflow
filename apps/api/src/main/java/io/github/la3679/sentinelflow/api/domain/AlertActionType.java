/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.domain;

/**
 * What was done to an alert.
 *
 * <p>Every one of these produces an append-only row. Nothing in an alert's history is ever
 * updated, because the history is what an audit reads.
 */
public enum AlertActionType {
    CREATED,
    ASSIGNED,
    UNASSIGNED,
    TRANSITIONED,
    NOTE_ADDED,
    PRIORITY_CHANGED
}
