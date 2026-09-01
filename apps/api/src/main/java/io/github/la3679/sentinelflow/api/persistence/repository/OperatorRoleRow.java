/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.repository;

import java.util.UUID;

import io.github.la3679.sentinelflow.api.domain.RoleCode;

/**
 * One role held by one operator.
 *
 * <p>A flat pair rather than a nested collection, because the question this answers is asked about a
 * page of operators at once: "which roles do these twenty people hold". Fetching them per operator
 * would be the N+1 that a picker rendering twenty names would pay twenty times over, and a mapped
 * collection on {@link io.github.la3679.sentinelflow.api.persistence.entity.User} would put a lazy
 * association on an entity that is otherwise loaded for its name alone.
 *
 * <p>The caller groups these by {@link #operatorId}. That is three lines in a service and no lines
 * in the schema.
 */
public record OperatorRoleRow(UUID operatorId, RoleCode role) {}
