/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web.limit;

import java.io.IOException;

/**
 * A request body ran past the configured maximum while it was being read (ADR-0017 §3).
 *
 * <p><strong>An {@link IOException} rather than a runtime exception</strong>, because it is thrown
 * from inside {@code ServletInputStream.read} and that is the only checked type the signature allows.
 *
 * <p>It arrives at the exception handler wrapped, as the cause of whatever the JSON parser threw when
 * its input ended early. {@code ApiExceptionHandler} unwraps looking for it, which is why this is a
 * type rather than a message somebody would have to match on.
 */
public class RequestTooLargeException extends IOException {

    private static final long serialVersionUID = 1L;

    private final long maximumBytes;

    public RequestTooLargeException(long maximumBytes) {
        super("The request body exceeds the " + maximumBytes + " byte maximum this API accepts.");
        this.maximumBytes = maximumBytes;
    }

    public long maximumBytes() {
        return maximumBytes;
    }
}
