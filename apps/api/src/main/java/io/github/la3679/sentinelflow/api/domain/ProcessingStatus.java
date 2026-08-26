/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.domain;

/**
 * Where a transaction has got to in the assessment pipeline.
 *
 * <p>{@code FAILED} means the pipeline could not produce an assessment, not that the transaction
 * was rejected. A rejected transaction never becomes a row at all.
 */
public enum ProcessingStatus {
    PENDING,
    ASSESSED,
    FAILED
}
