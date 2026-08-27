/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.scoring.client;

/**
 * The scoring service did not answer, and the caller should degrade rather than fail.
 *
 * <p>Thrown when every attempt inside the budget failed, or when the circuit breaker is open and no
 * attempt was made. Both mean the same thing to a caller: <strong>write a degraded assessment scored
 * by rules alone</strong> (ADR-0008 §3). That is a real answer, not a null with a flag on it, which
 * is the whole reason the ruleset runs in this process.
 *
 * <p>Distinct from {@link ScoringRejectedException} because the two have opposite handling. This one
 * is expected and survivable; that one is a defect and is dead-lettered so somebody sees it.
 */
public class ScoringUnavailableException extends RuntimeException {

    public ScoringUnavailableException(String message) {
        super(message);
    }

    public ScoringUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
