/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.operator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.la3679.sentinelflow.api.domain.RoleCode;
import io.github.la3679.sentinelflow.api.persistence.entity.User;
import io.github.la3679.sentinelflow.api.persistence.repository.OperatorRoleRow;
import io.github.la3679.sentinelflow.api.persistence.repository.UserRepository;
import io.github.la3679.sentinelflow.api.web.dto.AlertAssigneeResponse;
import io.github.la3679.sentinelflow.api.web.dto.OperatorResponse;

/**
 * Who the operators are, and which of them an alert may be given to.
 *
 * <p>The whole of "how an assignee's identifier resolves to a person", which was the one decision
 * the console could not make for itself: the alert held a {@code UUID}, nothing turned it into a
 * name, and the screen said so rather than offering a control that could not work. ADR-0019 records
 * the decision; this is the smallest implementation of it that is architecturally correct.
 *
 * <h2>Two questions, and they are not the same question</h2>
 *
 * <ul>
 *   <li>{@link #assignable(Pageable)} — <em>who may be given an alert</em>. Active, and holding a
 *       role that can work one. This populates a picker.
 *   <li>{@link #resolve(Collection)} — <em>who holds these alerts</em>. Anybody, including an
 *       operator whose account has since been disabled, because an alert assigned to somebody who
 *       has left is still assigned to them.
 * </ul>
 *
 * Collapsing the two would produce one of two bugs, and both are worse than the extra method: a
 * picker that offers a disabled operator the server will refuse, or a queue that silently blanks the
 * assignee of every alert held by somebody who has left.
 *
 * <h2>Read-only, and bounded</h2>
 *
 * Nothing here writes. Both methods issue a fixed number of queries regardless of how many rows come
 * back — two for a page of operators, one for a page of alerts — so neither is an N+1 waiting for a
 * larger page size.
 */
@Service
public class OperatorDirectory {

    private final UserRepository users;

    public OperatorDirectory(UserRepository users) {
        this.users = users;
    }

    /**
     * One page of the operators an alert may be given to.
     *
     * <p>Two queries: the page, then every role held by the operators on it. Not one query with a
     * join, because a join to a to-many multiplies the rows and makes the page size mean something
     * other than "this many operators".
     */
    @Transactional(readOnly = true)
    public Page<OperatorResponse> assignable(Pageable pageable) {
        Page<User> page = users.findAssignableOperators(pageable);
        Map<UUID, List<RoleCode>> roles =
                rolesFor(page.getContent().stream().map(User::getId).toList());
        return page.map(operator -> OperatorResponse.of(operator, roles.getOrDefault(operator.getId(), List.of())));
    }

    /**
     * The people behind a set of assignee identifiers, for a page of alerts.
     *
     * <p>One query for the whole page, keyed for the caller to look each row up in. An identifier
     * with no row is simply absent from the map rather than mapped to a placeholder: the alert then
     * publishes a null assignee, which is what an unresolvable identifier honestly is.
     *
     * <p><strong>A null or empty input does no work at all.</strong> A page of alerts none of which
     * is assigned is the common case on a healthy queue, and it must not cost a query.
     */
    @Transactional(readOnly = true)
    public Map<UUID, AlertAssigneeResponse> resolve(Collection<UUID> operatorIds) {
        Set<UUID> wanted = operatorIds == null
                ? Set.of()
                : operatorIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (wanted.isEmpty()) {
            return Map.of();
        }
        return users.findAllById(wanted).stream().collect(Collectors.toMap(User::getId, AlertAssigneeResponse::of));
    }

    /** Every role held by each of these operators, grouped, in one query. */
    private Map<UUID, List<RoleCode>> rolesFor(List<UUID> operatorIds) {
        if (operatorIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<RoleCode>> grouped = new LinkedHashMap<>();
        for (OperatorRoleRow row : users.findRoleCodesFor(operatorIds)) {
            grouped.computeIfAbsent(row.operatorId(), id -> new ArrayList<>()).add(row.role());
        }
        return grouped;
    }
}
