/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.domain;

/**
 * Whether a reason code came from a deterministic rule or from the model.
 *
 * <p>An analyst defending a decision needs to know which: a rule can be read, and a model score
 * can only be attributed.
 */
public enum ReasonSource {
    RULE,
    MODEL
}
