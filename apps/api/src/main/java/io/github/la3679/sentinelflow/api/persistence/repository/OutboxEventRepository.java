/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import io.github.la3679.sentinelflow.api.persistence.entity.OutboxEvent;

/**
 * The outbox.
 *
 * <p>Only writes for now. The relay's claiming query is Phase 3's next unit and needs
 * {@code FOR UPDATE SKIP LOCKED} (ADR-0005), which is not expressible as a derived method name.
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {}
