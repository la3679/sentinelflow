/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import io.github.la3679.sentinelflow.api.domain.AlertStatus;

/**
 * A request to move an alert.
 *
 * <p><strong>What is validated here and what is not.</strong> That a status was named and is one of
 * the six, that a version was supplied, and that a note fits the column: all shape, all decidable
 * without reading a row. Whether the move is legal from where the alert currently is depends on the
 * alert, changes between requests, and is a 409 rather than a 400 — so it is deliberately not a
 * constraint on this object.
 *
 * <p>{@code expectedVersion} is required rather than optional. An optional one would make the safe
 * call the longer one to write, and the unsafe call the default.
 *
 * @param targetStatus where to move the alert
 * @param expectedVersion the version the caller believes the alert is at. Zero is legitimate: it is
 *     the version of an alert nobody has acted on yet, which is the one most likely to be
 *     transitioned first.
 * @param note the actor's own words, stored as text and never interpreted. Bounded at the column's
 *     2000 characters.
 */
public record AlertTransitionRequest(
        @NotNull AlertStatus targetStatus,
        @NotNull @PositiveOrZero Long expectedVersion,
        @Size(max = 2000) String note) {}
