/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.service.exception;

/**
 * The reporting window a caller asked for is not one this API will answer.
 *
 * <p>Inverted, or wider than the maximum. Both are 422 rather than 400: the request parses, every
 * value in it is a well-formed instant, and what is wrong is the relationship between two of them.
 *
 * <p><strong>An inverted window is the one worth refusing loudly.</strong> It matches no rows, so
 * the honest-looking answer is an empty report — which reads as "there were no alerts" and is the
 * worst thing a report can say when it is not true.
 */
public class InvalidWindowException extends RuntimeException {

    public InvalidWindowException(String reason) {
        super("Invalid reporting window: " + reason);
    }
}
