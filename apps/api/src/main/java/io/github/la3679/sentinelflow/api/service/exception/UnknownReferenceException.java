/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.service.exception;

/**
 * A business reference in a request names nothing that exists.
 *
 * <p>Distinct from a validation failure. {@code ACC-999999} is a well-formed account reference; it
 * is simply not one of ours, and answering "422, malformed" would send the caller looking for a
 * typo that is not there. The message names the reference the caller sent, which is their own
 * input and safe to echo, and never anything about what does exist.
 */
public class UnknownReferenceException extends RuntimeException {

    private final String field;
    private final String reference;

    public UnknownReferenceException(String field, String reference) {
        super("No " + field + " matches " + reference);
        this.field = field;
        this.reference = reference;
    }

    public String field() {
        return field;
    }

    public String reference() {
        return reference;
    }
}
