/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.la3679.sentinelflow.api.domain.IngestionSource;
import io.github.la3679.sentinelflow.api.service.TransactionIngestionService;
import io.github.la3679.sentinelflow.api.service.TransactionIngestionService.IngestionOutcome;
import io.github.la3679.sentinelflow.api.web.dto.TransactionAcceptedResponse;
import io.github.la3679.sentinelflow.api.web.dto.TransactionRequest;

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

    private final TransactionIngestionService ingestion;

    public TransactionController(TransactionIngestionService ingestion) {
        this.ingestion = ingestion;
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
}
