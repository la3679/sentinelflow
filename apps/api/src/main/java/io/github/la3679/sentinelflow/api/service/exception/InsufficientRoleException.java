/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.service.exception;

import io.github.la3679.sentinelflow.api.domain.ActorRole;

/**
 * The actor is authenticated and is not allowed to do this.
 *
 * <p>A 403 rather than a 401: the caller proved who they are, and the answer is still no. The
 * distinction matters to a client, which should re-authenticate on one and never on the other.
 *
 * <p>The message names the role required, which is a property of the operation rather than of this
 * caller. What it never names is anything about the resource, because a caller who may not act on
 * something is a caller who should not learn about it either.
 */
public class InsufficientRoleException extends RuntimeException {

    private final ActorRole held;
    private final ActorRole required;

    public InsufficientRoleException(ActorRole held, ActorRole required, String operation) {
        super(operation + " requires " + required + " and the actor holds " + held);
        this.held = held;
        this.required = required;
    }

    public ActorRole held() {
        return held;
    }

    public ActorRole required() {
        return required;
    }
}
