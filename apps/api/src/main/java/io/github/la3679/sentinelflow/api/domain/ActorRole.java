/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.domain;

/**
 * The role an actor held when they did something.
 *
 * <p>Recorded on the action rather than looked up from the user later: a user's roles change, and
 * an audit trail has to say what was true at the time.
 */
public enum ActorRole {
    ANALYST,
    ADMINISTRATOR,
    AUDITOR,
    SYSTEM
}
