/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.domain;

/**
 * What kind of change an {@code alert.updated} event describes.
 *
 * <p>Explicit on the event so a consumer can filter without comparing before-and-after fields.
 * {@code alert-updated.v1.json} carries every field for every change type, so "the status is the
 * same in both halves" is true of an assignment and of a note, and a consumer inferring the kind
 * from what moved would have to know which combinations are possible.
 *
 * <p>Deliberately not the same enum as {@link AlertActionType}, which records what a person did to
 * an alert in the audit trail. That one distinguishes {@code ASSIGNED} from {@code UNASSIGNED}
 * because an audit reader asks which happened; this one calls both {@code ASSIGNMENT} because a
 * consumer routing on it does not, and the payload's {@code assignee} says which it was. Two enums
 * that answer two questions, rather than one that answers neither well.
 */
public enum AlertChangeType {
    STATUS_TRANSITION,
    ASSIGNMENT,
    PRIORITY_CHANGE,
    NOTE_ADDED
}
