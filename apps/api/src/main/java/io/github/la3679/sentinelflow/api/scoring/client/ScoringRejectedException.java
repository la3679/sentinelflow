/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.scoring.client;

/**
 * The scoring service rejected the request, and it will reject it identically next time.
 *
 * <p>A 4xx. The contract's 422 is the one that matters: the request does not satisfy the contract,
 * and no amount of retrying makes it satisfy the contract.
 *
 * <p><strong>Never degraded, and never retried.</strong> ADR-0008 §3 says it is dead-lettered so it
 * is visible rather than absorbed as a degraded assessment: a contract mismatch between two services
 * in one repository is a defect to fix, not a condition to tolerate, and a pipeline that quietly
 * turned it into a rules-only score would hide it behind a perfectly healthy-looking dashboard.
 *
 * <p>The message carries the status and the problem document's {@code title} and {@code detail},
 * which name the offending fields and — by the scoring service's own design — nothing about their
 * values.
 */
public class ScoringRejectedException extends RuntimeException {

    private final int status;

    public ScoringRejectedException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
