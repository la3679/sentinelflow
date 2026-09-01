/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web.dto;

import java.util.List;
import java.util.UUID;

import io.github.la3679.sentinelflow.api.domain.RoleCode;
import io.github.la3679.sentinelflow.api.persistence.entity.User;

/**
 * An operator an alert may be given to.
 *
 * <p>Field-for-field with the {@code Operator} schema in {@code contracts/openapi/}.
 *
 * <h2>What is deliberately not here</h2>
 *
 * No credential, no email address, no last-login time, no status. The status is absent because every
 * operator this endpoint returns is active - a disabled one is filtered out by the query rather than
 * returned with a flag for a client to check and forget to check. Publishing a field whose value is
 * always the same invites a consumer to build a filter on it that will silently never fire.
 *
 * <p>{@code username} is here beside {@code displayName} because a display name is not unique. Two
 * operators called "A. Analyst" are distinguishable in a picker only by something that is, and the
 * username is unique by constraint (V1).
 *
 * <p>The roles are the ones the operator holds, for a console that wants to say "administrator"
 * beside a name. **They authorize nothing** - every authorization decision is the server's, made
 * from the token on the request, and a client that hid a control based on this field would be making
 * a user-experience choice rather than a security one.
 */
public record OperatorResponse(UUID operatorId, String username, String displayName, List<RoleCode> roles) {

    public static OperatorResponse of(User operator, List<RoleCode> roles) {
        return new OperatorResponse(operator.getId(), operator.getUsername(), operator.getDisplayName(), roles);
    }
}
