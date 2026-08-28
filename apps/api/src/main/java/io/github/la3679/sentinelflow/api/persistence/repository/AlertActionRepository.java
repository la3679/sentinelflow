/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import io.github.la3679.sentinelflow.api.persistence.entity.AlertAction;

/**
 * The append-only history of everything done to an alert.
 *
 * <p><strong>No update and no delete are ever exposed here</strong>, and the table's own comment
 * says the same: an audit trail a service can rewrite is not an audit trail. {@link JpaRepository}
 * inherits {@code save} and {@code delete}; nothing in this application calls the second, and
 * ADR-0005 §5 makes any administrative correction a new row rather than an edit to an old one.
 */
public interface AlertActionRepository extends JpaRepository<AlertAction, UUID> {

    /** One alert's history, oldest first, which is the order an investigation is read in. */
    List<AlertAction> findByAlertIdOrderByOccurredAtAsc(UUID alertId);
}
