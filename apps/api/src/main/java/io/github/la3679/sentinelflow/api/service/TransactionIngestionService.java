/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.service;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import io.github.la3679.sentinelflow.api.domain.IngestionSource;
import io.github.la3679.sentinelflow.api.persistence.entity.Merchant;
import io.github.la3679.sentinelflow.api.persistence.entity.TransactionRecord;
import io.github.la3679.sentinelflow.api.service.exception.IdempotencyConflictException;
import io.github.la3679.sentinelflow.api.service.exception.UnknownReferenceException;
import io.github.la3679.sentinelflow.api.web.dto.TransactionRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Ingestion, including what happens when the same request arrives twice.
 *
 * <p><strong>The database is what makes this idempotent, not this class.</strong> The lookup below
 * is an optimisation: it answers the common retry without generating a reference or touching the
 * outbox. It is not the guarantee. Two retries racing in different threads or different instances
 * both pass that lookup, and what stops the second from creating a duplicate transaction is
 * {@code transactions_idempotency_unique}. Application code cannot close that window — a
 * check-then-insert has one by construction — so this code is written to lose that race gracefully
 * rather than to pretend it cannot happen.
 *
 * <p>Three outcomes, and they are genuinely different things:
 *
 * <ul>
 *   <li><strong>Created.</strong> A new transaction. {@code 202}.
 *   <li><strong>Replayed.</strong> The same key with the same payload. The original result,
 *       unchanged, and nothing new written. {@code 200}, because {@code 202} would tell a caller
 *       something was accepted that was not.
 *   <li><strong>Conflict.</strong> The same key with a <em>different</em> payload. {@code 409}.
 *       Returning the original result here would leave the caller believing a transaction it never
 *       submitted had been recorded, and would hide a broken key generator indefinitely.
 * </ul>
 *
 * <p>Not {@code @Transactional}. Recovering from the lost race requires reading in a transaction
 * that a constraint violation has not already doomed, so the boundaries live in
 * {@link TransactionWriter} and this class orchestrates across them.
 */
@Service
public class TransactionIngestionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionIngestionService.class);

    /** The metric name, here so the test asserts against the shipped string. */
    static final String INGESTED_METRIC = "sentinelflow.transactions.ingested";

    private final TransactionWriter writer;
    private final MeterRegistry meters;

    public TransactionIngestionService(TransactionWriter writer, MeterRegistry meters) {
        this.writer = writer;
        this.meters = meters;
    }

    public IngestionOutcome ingest(TransactionRequest request, UUID correlationId, IngestionSource source) {
        Optional<TransactionRecord> alreadyAccepted =
                writer.findAccepted(request.accountReference(), request.idempotencyKey());
        if (alreadyAccepted.isPresent()) {
            return replayOrConflict(alreadyAccepted.get(), request, source);
        }

        try {
            TransactionRecord created = writer.write(request, correlationId, source);
            count(source, "created");
            return IngestionOutcome.created(created);
        } catch (DataIntegrityViolationException raceLost) {
            // Another request committed this key between the lookup and the
            // insert. That is normal traffic under at-least-once ingestion, not
            // an error - the constraint did exactly what it is for.
            return resolveLostRace(request, source, raceLost);
        }
    }

    private IngestionOutcome resolveLostRace(
            TransactionRequest request, IngestionSource source, DataIntegrityViolationException raceLost) {
        Optional<TransactionRecord> winner = writer.findAccepted(request.accountReference(), request.idempotencyKey());
        if (winner.isEmpty()) {
            // The violation was not the idempotency constraint, so this is a
            // different defect wearing the same exception type. Rethrowing keeps
            // it visible instead of reporting a duplicate that does not exist.
            throw raceLost;
        }
        log.debug("Concurrent submission of idempotency key on account {}", request.accountReference());
        return replayOrConflict(winner.get(), request, source);
    }

    private IngestionOutcome replayOrConflict(
            TransactionRecord existing, TransactionRequest request, IngestionSource source) {
        UUID merchantId = writer.findMerchant(request.merchantReference())
                .map(Merchant::getId)
                .orElseThrow(() -> new UnknownReferenceException("merchantReference", request.merchantReference()));

        if (!writer.matches(existing, request, merchantId)) {
            count(source, "conflict");
            throw new IdempotencyConflictException(request.idempotencyKey());
        }
        count(source, "replayed");
        return IngestionOutcome.replayed(existing);
    }

    /**
     * Ingestion throughput, by where the transaction came from and what ingestion did with it.
     *
     * <p>Nine series and no more: {@link IngestionSource} has three values fixed in an enum, and the
     * outcome is one of the three this class documents. Neither comes from the request, which is
     * ADR-0016 §2's rule — an account or a merchant here would be one series per party, retained for
     * a week, holding an identifier in a label.
     *
     * <p><strong>Replays and conflicts are counted, not just creations.</strong> A caller whose
     * idempotency keys have broken shows up as a conflict rate rather than as a support ticket, and
     * a retry storm shows up as a replay rate rather than as throughput that looks flat while the
     * database is working. An unknown reference is not counted here: it never reaches ingestion as a
     * transaction, and {@code http.server.requests} already carries its 4xx.
     */
    private void count(IngestionSource source, String outcome) {
        Counter.builder(INGESTED_METRIC)
                .tag("source", source.name())
                .tag("outcome", outcome)
                .description("Transactions offered to ingestion, by origin and by what ingestion did with them")
                .register(meters)
                .increment();
    }

    /**
     * What ingestion did, and the transaction it did it to.
     *
     * @param transaction the accepted transaction, whether it was created now or earlier
     * @param replayed true when this request created nothing because the key had already been used
     */
    public record IngestionOutcome(TransactionRecord transaction, boolean replayed) {

        static IngestionOutcome created(TransactionRecord transaction) {
            return new IngestionOutcome(transaction, false);
        }

        static IngestionOutcome replayed(TransactionRecord transaction) {
            return new IngestionOutcome(transaction, true);
        }
    }
}
