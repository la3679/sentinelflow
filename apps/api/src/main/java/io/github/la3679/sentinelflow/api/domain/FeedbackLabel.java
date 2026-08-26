/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.domain;

/**
 * An analyst's disposition of an assessment, and the label a future model trains on.
 *
 * <p>{@code INCONCLUSIVE} exists so that an analyst who cannot tell is not forced to guess.
 * Training on a coerced label is worse than training on fewer of them.
 */
public enum FeedbackLabel {
    TRUE_POSITIVE,
    FALSE_POSITIVE,
    INCONCLUSIVE
}
