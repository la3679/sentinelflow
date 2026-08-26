/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.messaging;

/** A publication attempt failed. Retryable: the relay schedules another attempt. */
public class EventPublicationException extends RuntimeException {

    public EventPublicationException(String message, Throwable cause) {
        super(message, cause);
    }

    public EventPublicationException(String message) {
        super(message);
    }
}
