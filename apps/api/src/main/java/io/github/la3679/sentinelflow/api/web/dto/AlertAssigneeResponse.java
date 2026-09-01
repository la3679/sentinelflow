/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web.dto;

import java.util.UUID;

import io.github.la3679.sentinelflow.api.persistence.entity.User;

/**
 * The person an alert is with, as a reader of the alert sees them.
 *
 * <p>Field-for-field with the {@code AlertAssignee} schema in {@code contracts/openapi/}.
 *
 * <h2>Why the alert carries this rather than the console resolving it</h2>
 *
 * A queue row has to render a person. If the identifier alone were published, every client would
 * need the operator directory loaded before it could draw a single row, and a row whose assignee had
 * since been disabled would render as a blank or as a raw UUID depending on how carefully each
 * client had thought about it. Resolving it once, server-side, makes every client agree.
 *
 * <p><strong>This is not the operator directory.</strong> {@code GET /operators} answers "who may be
 * given an alert" and excludes anybody who no longer may; this answers "who has this one", and must
 * still name somebody whose account has since been disabled. An alert assigned last week to an
 * operator who left is not unassigned, and an audit trail that quietly forgot them would be worse
 * than one that names them.
 *
 * <p>No roles here, deliberately. The roles an assignee holds are a property of the operator rather
 * than of this alert, they can change after the assignment, and a reader who needs them is asking a
 * question {@code GET /operators} answers.
 */
public record AlertAssigneeResponse(UUID operatorId, String username, String displayName) {

    public static AlertAssigneeResponse of(User operator) {
        return new AlertAssigneeResponse(operator.getId(), operator.getUsername(), operator.getDisplayName());
    }
}
