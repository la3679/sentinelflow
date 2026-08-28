/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web;

import java.time.Instant;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.la3679.sentinelflow.api.domain.IngestionSource;
import io.github.la3679.sentinelflow.api.domain.RiskBand;
import io.github.la3679.sentinelflow.api.service.TransactionIngestionService;
import io.github.la3679.sentinelflow.api.service.TransactionIngestionService.IngestionOutcome;
import io.github.la3679.sentinelflow.api.service.TransactionQueryService;
import io.github.la3679.sentinelflow.api.web.dto.PageResponse;
import io.github.la3679.sentinelflow.api.web.dto.RiskAssessmentResponse;
import io.github.la3679.sentinelflow.api.web.dto.TransactionAcceptedResponse;
import io.github.la3679.sentinelflow.api.web.dto.TransactionRequest;
import io.github.la3679.sentinelflow.api.web.dto.TransactionResponse;

/**
 * Transaction ingestion.
 *
 * <p>The controller validates, delegates, and maps. No business rule, no repository call, no
 * transaction boundary — those are the service's, per {@code .claude/rules/java.md}. A JPA entity
 * never crosses this boundary either: the response is built from one, and is not one.
 *
 * <p><strong>202, not 201.</strong> The transaction is durable when this returns; its assessment is
 * not, because scoring is asynchronous. {@code 201 Created} would suggest a completed resource, and
 * a client following that logic would immediately request an assessment that does not exist.
 *
 * <p><strong>200 for a replay.</strong> A resubmitted idempotency key returns the original result
 * with {@code 200}, so the status code itself distinguishes "accepted just now" from "you already
 * sent this". Both bodies are identical, deliberately: a retry should not have to parse a different
 * shape to find out what happened.
 */
@RestController
@RequestMapping(path = "/api/v1/transactions", produces = MediaType.APPLICATION_JSON_VALUE)
public class TransactionController {

    /**
     * The largest page this endpoint will serve.
     *
     * <p>Refused above it rather than clamped, for the reason the contract gives: silently returning
     * less than was asked for is how a client ends up with a quiet data-loss bug.
     */
    static final int MAX_PAGE_SIZE = 200;

    private final TransactionIngestionService ingestion;
    private final TransactionQueryService queries;

    public TransactionController(TransactionIngestionService ingestion, TransactionQueryService queries) {
        this.ingestion = ingestion;
        this.queries = queries;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<TransactionAcceptedResponse> ingest(
            @Valid @RequestBody TransactionRequest request, HttpServletRequest httpRequest) {

        UUID correlationId = CorrelationIdFilter.currentOrNew(httpRequest);
        // API, always. The source is a property of how the transaction entered
        // the system, and everything arriving here entered through the API -
        // a generator or a replay writes through its own path and says so.
        IngestionOutcome outcome = ingestion.ingest(request, correlationId, IngestionSource.API);

        TransactionAcceptedResponse body = TransactionAcceptedResponse.of(
                outcome.transaction().getId(), outcome.transaction().getTransactionReference(), correlationId);

        return ResponseEntity.status(outcome.replayed() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(body);
    }

    /**
     * One page of transactions, newest first.
     *
     * <p><strong>Authenticated, unlike the ingestion above it.</strong> Posting a transaction is a
     * machine-to-machine surface whose caller is a payment pipeline; reading them is an operator
     * looking at other people's activity, and those are not the same permission. The security
     * configuration's {@code permitAll} names the {@code POST} specifically, so this is authenticated
     * by {@code anyRequest().authenticated()} without anything having to be added for it.
     *
     * <p>Readable by any authenticated role, {@code AUDITOR} included: read-only describes what
     * somebody may do, not what they may see.
     *
     * <p>The ordering is fixed rather than client-supplied, for the reason the alert queue's is.
     */
    @GetMapping
    PageResponse<TransactionResponse> list(
            @RequestParam(required = false) String accountReference,
            @RequestParam(required = false) RiskBand riskBand,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant occurredAfter,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant occurredBefore,
            @RequestParam(defaultValue = "0") @PositiveOrZero int page,
            @RequestParam(defaultValue = "50") @Positive @Max(MAX_PAGE_SIZE) int size) {

        return PageResponse.of(
                queries.list(accountReference, riskBand, occurredAfter, occurredBefore, PageRequest.of(page, size)),
                TransactionResponse::of);
    }

    /** One transaction, for the page an analyst opens from an alert. */
    @GetMapping("/{transactionId}")
    TransactionResponse get(@PathVariable UUID transactionId) {
        return TransactionResponse.of(queries.get(transactionId));
    }

    /**
     * The decision behind one transaction.
     *
     * <p><strong>A {@code 404} here is a normal outcome</strong>, not a fault: scoring happens after
     * ingestion answers, so a transaction legitimately has no assessment for a while. A client
     * polling for one reads it as "not yet".
     */
    @GetMapping("/{transactionId}/assessment")
    RiskAssessmentResponse assessment(@PathVariable UUID transactionId) {
        return RiskAssessmentResponse.of(queries.assessmentFor(transactionId));
    }
}
