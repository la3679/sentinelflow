/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.service.exception;

import java.util.UUID;

/**
 * No transaction has this identifier.
 *
 * <p>The message names the identifier the caller sent, which is their own input, and nothing about
 * what does exist — for the reason {@link AlertNotFoundException} gives.
 */
public class TransactionNotFoundException extends RuntimeException {

    private final UUID transactionId;

    public TransactionNotFoundException(UUID transactionId) {
        super("No transaction has identifier " + transactionId);
        this.transactionId = transactionId;
    }

    public UUID transactionId() {
        return transactionId;
    }
}
