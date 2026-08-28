/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /**
     * One page of an alert's history, newest first, which is what the API returns.
     *
     * <p>Newest first because a reader opening an alert asks what just happened, and the contract
     * says so. The identifier breaks ties, and that is load-bearing rather than tidy: several rows
     * can share an {@code occurred_at} — a transition and the note that explains it are written in
     * the same instant by the same request — and once a result is cut into pages, <em>which</em> rows
     * land on which page would otherwise vary between two identical requests. UUIDv7 sorts by
     * creation, so the tie-break is also chronological rather than arbitrary.
     */
    Page<AlertAction> findByAlertIdOrderByOccurredAtDescIdDesc(UUID alertId, Pageable pageable);
}
