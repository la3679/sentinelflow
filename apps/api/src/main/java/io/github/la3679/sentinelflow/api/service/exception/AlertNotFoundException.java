/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.service.exception;

import java.util.UUID;

/**
 * No alert has this identifier.
 *
 * <p>The message names the identifier the caller sent, which is their own input, and nothing about
 * what does exist. A 404 that distinguished "never existed" from "exists and is not yours" would
 * answer a question the caller was not entitled to ask.
 */
public class AlertNotFoundException extends RuntimeException {

    private final UUID alertId;

    public AlertNotFoundException(UUID alertId) {
        super("No alert has identifier " + alertId);
        this.alertId = alertId;
    }

    public UUID alertId() {
        return alertId;
    }
}
