/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.la3679.sentinelflow.api.operator.OperatorDirectory;
import io.github.la3679.sentinelflow.api.web.dto.OperatorResponse;
import io.github.la3679.sentinelflow.api.web.dto.PageResponse;

/**
 * The operators an alert may be given to.
 *
 * <p>Exists so a console can offer a name instead of asking an analyst to type a UUID. ADR-0019
 * records why this is a directory of its own rather than a field on something else.
 *
 * <h2>Readable by every authenticated role, and that is deliberate</h2>
 *
 * An {@code AUDITOR} may read this. They cannot assign anything - ADR-0012 §4 makes the role
 * read-only and the server refuses every mutation from it - but an auditor reading an alert assigned
 * to somebody needs to know who that is, and the alert already tells them. Withholding the directory
 * from a role that can already see every assignee on every alert would protect nothing.
 *
 * <h2>Paged and bounded like every other list</h2>
 *
 * The same cap as the alert queue, for the same reason: an endpoint whose response grows with the
 * dataset is a denial-of-service primitive. Four operators exist today, which is exactly the
 * situation in which somebody argues the bound is unnecessary and the endpoint quietly becomes
 * unbounded before anybody adds a fifth.
 */
@RestController
@RequestMapping(path = "/api/v1/operators", produces = MediaType.APPLICATION_JSON_VALUE)
public class OperatorController {

    /** The contract's cap, enforced rather than clamped - see {@link AlertController#MAX_PAGE_SIZE}. */
    static final int MAX_PAGE_SIZE = 200;

    private final OperatorDirectory operators;

    public OperatorController(OperatorDirectory operators) {
        this.operators = operators;
    }

    /**
     * One page of the operators who may hold an alert.
     *
     * <p>Ordered by display name, so a picker reads the way a person expects rather than in
     * insertion order.
     *
     * <p><strong>This is an affordance, not an authorization.</strong> Everybody here would be
     * accepted by the assignment endpoint today, and that is a property of the two using the same
     * rule rather than a promise the endpoint makes. The server checks again on the way in, because
     * a client is free to send an identifier this endpoint never gave it.
     */
    @GetMapping
    PageResponse<OperatorResponse> assignable(
            @RequestParam(defaultValue = "0") @PositiveOrZero int page,
            @RequestParam(defaultValue = "20") @Positive @Max(MAX_PAGE_SIZE) int size) {

        return PageResponse.of(operators.assignable(PageRequest.of(page, size)), operator -> operator);
    }
}
