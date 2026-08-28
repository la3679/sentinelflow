/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A login attempt.
 *
 * <p><strong>The bounds are the point, not the shape.</strong> A username is checked against the
 * length the column allows and a password against a length no legitimate one exceeds, because both
 * are unauthenticated input on an open endpoint and BCrypt costs real time per attempt. Nothing here
 * validates the <em>format</em> of either: a username that cannot exist and one that merely does not
 * must produce the same refusal, and a pattern rejected at the boundary would answer a question the
 * login path deliberately does not.
 *
 * <p>{@code password} is a {@code String} rather than a {@code char[]}. The immutable copy is
 * unavoidable — Jackson materialises one to bind the field — and pretending otherwise with a type
 * that is cleared afterwards would be theatre.
 *
 * @param username the operator's name, lower-case in the database and compared as sent
 * @param password never logged, never echoed, and never part of any response
 */
public record LoginRequest(
        @NotBlank @Size(max = 64) String username,
        @NotBlank @Size(max = 200) String password) {

    @Override
    public String toString() {
        // Records generate a toString that prints every component. This one
        // would print the password into any log line, exception message or
        // debugger view that touched the request, and the endpoint that handles
        // it is the one place that is guaranteed to be looked at.
        return "LoginRequest[username=" + username + ", password=***]";
    }
}
