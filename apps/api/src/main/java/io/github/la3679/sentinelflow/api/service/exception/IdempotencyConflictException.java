/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.service.exception;

/**
 * An idempotency key was reused on the same account with a different payload.
 *
 * <p>This is not a duplicate submission and must not be treated as one. A retry sends the same
 * request again and gets the original result back; this caller sent something else under a key it
 * had already used, which means its key generation is broken. Returning the original result would
 * hide that, and the caller would go on believing a transaction it never made was recorded.
 *
 * <p>Answered as {@code 409}, per the OpenAPI contract.
 */
public class IdempotencyConflictException extends RuntimeException {

    private final String idempotencyKey;

    public IdempotencyConflictException(String idempotencyKey) {
        super("Idempotency key " + idempotencyKey + " was already used on this account with a different payload");
        this.idempotencyKey = idempotencyKey;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }
}
