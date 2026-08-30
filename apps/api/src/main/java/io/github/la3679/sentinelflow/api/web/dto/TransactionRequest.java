/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web.dto;

import java.time.Instant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import io.github.la3679.sentinelflow.api.domain.TransactionChannel;
import io.github.la3679.sentinelflow.api.domain.TransactionType;

/**
 * The ingestion request body, validated at the boundary.
 *
 * <p>Every constraint here mirrors the {@code TransactionRequest} schema in
 * {@code contracts/openapi/}, and every one of them also exists as a database {@code CHECK}. The
 * duplication is intentional and the two are not redundant: the constraint rejects a bad row from
 * any writer, and the annotation turns that rejection into a {@code 422} naming the offending field
 * instead of a {@code 500} from a constraint violation nobody can act on.
 *
 * <p><strong>References, not identifiers.</strong> A caller names an account as {@code ACC-000123}
 * rather than by UUID. Internal identifiers are not a client's business, and a reference is what a
 * person has in front of them.
 *
 * <p><strong>{@code occurredAt} is required and is not "now".</strong> A replayed scenario occurred
 * when the scenario says it did; defaulting it to ingestion time would make every replayed
 * transaction look as though it happened at import and would destroy every velocity feature
 * computed from it.
 *
 * <p>The record has no {@code additionalProperties: false} equivalent in Bean Validation; Jackson is
 * configured to reject unknown fields instead, so a client's typo fails rather than being ignored.
 */
public record TransactionRequest(
        @NotNull(message = "must be present")
        @Size(min = 8, max = 128, message = "must be between 8 and 128 characters")
        String idempotencyKey,

        @NotNull(message = "must be present") @Pattern(regexp = "^ACC-[0-9]{6}$", message = "must look like ACC-000123")
        String accountReference,

        @NotNull(message = "must be present") @Pattern(regexp = "^MER-[0-9]{4}$", message = "must look like MER-0042")
        String merchantReference,

        @NotNull(message = "must be one of PURCHASE, REFUND, TRANSFER, WITHDRAWAL, DEPOSIT")
        TransactionType type,

        @NotNull(message = "must be one of CARD_PRESENT, CARD_NOT_PRESENT, ONLINE_TRANSFER, ATM," + " DIRECT_DEBIT")
        TransactionChannel channel,

        @NotNull(message = "must be present") @Valid AmountRequest amount,

        @NotNull(message = "must be present")
        @Pattern(regexp = "^[A-Z]{2}$", message = "must be an ISO 3166-1 alpha-2 code")
        String originCountry,
        // Nullable by design: a direct debit has no device, and null says that.
        // The pattern only applies when a value is present.
        @Pattern(regexp = "^DEV-[0-9a-f]{12}$", message = "must look like DEV-4f2a91c0be73, or be omitted")
        String deviceReference,

        @NotNull(message = "must be present") Instant occurredAt) {

    /**
     * What ingestion is allowed to say about a request it is holding.
     *
     * <p>The reference, the type and the channel are what an operator needs to find the thing; the
     * amount, the device handle and the caller-chosen idempotency key are the three things ADR-0016
     * §4 forbids at every level. A record prints all ten components by default, so this override is
     * what stands between one careless {@code log.debug("{}", request)} and a disclosure.
     *
     * <p>The idempotency key is here for a second reason as well: it is caller-controlled text, and
     * caller-controlled text in a log is the injection surface {@code CorrelationIdFilter} already
     * refuses to reflect.
     */
    @Override
    public String toString() {
        return "TransactionRequest[account=" + accountReference + " merchant=" + merchantReference + " type=" + type
                + " channel=" + channel + " amount, device and key redacted]";
    }
}
