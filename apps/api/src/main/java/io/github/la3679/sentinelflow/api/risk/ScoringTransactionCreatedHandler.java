/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.risk;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.la3679.sentinelflow.api.domain.DlqFailureClass;
import io.github.la3679.sentinelflow.api.messaging.EventEnvelope;
import io.github.la3679.sentinelflow.api.messaging.consumer.NonRetryableEventException;
import io.github.la3679.sentinelflow.api.messaging.consumer.TransactionCreatedHandler;
import io.github.la3679.sentinelflow.api.messaging.payload.TransactionCreatedPayload;
import io.github.la3679.sentinelflow.api.persistence.entity.RiskAssessment;
import io.github.la3679.sentinelflow.api.persistence.entity.TransactionRecord;
import io.github.la3679.sentinelflow.api.persistence.repository.TransactionRepository;
import io.github.la3679.sentinelflow.api.scoring.client.ScoringRejectedException;

/**
 * Scores an accepted transaction. The first implementation of the port Phase 3 left open.
 *
 * <p>Phase 3's consumer injects a <em>list</em> of {@link TransactionCreatedHandler} and dispatches
 * to every one, precisely so that this arrives as a new bean rather than as an edit to the consumer.
 * Nothing in the messaging package changes to switch scoring on.
 *
 * <h2>This class is a translation, and that is all it is</h2>
 *
 * The work is {@link RiskAssessmentService}'s. What lives here is the one thing that belongs to
 * delivery rather than to risk: turning the three outcomes of an assessment into the two answers a
 * Kafka consumer understands.
 *
 * <ul>
 *   <li>An assessment was written — return. The consumer commits the offset.
 *   <li>{@link ScoringRejectedException} — a
 *       {@link NonRetryableEventException}, so the record is dead-lettered rather than retried. The
 *       scoring service refused the request and will refuse it identically next time; retrying costs
 *       the whole partition queued behind it and changes nothing (ADR-0006 §4, ADR-0008 §3).
 *   <li>Anything else — thrown as it is, which the consumer treats as retryable. A handler failure is
 *       usually a dependency being briefly unavailable, and the scoring service being unavailable is
 *       not even that: it never reaches here, because the service degrades to rules.
 * </ul>
 *
 * <p><strong>The translation lives here and not in the service</strong> because "dead letter" is a
 * delivery concept. A rescoring endpoint in Phase 5 will call the same service from an HTTP request
 * where there is no partition to block and no topic to dead-letter to, and it must be free to answer
 * a rejection with a problem document instead.
 *
 * <h2>Transactions</h2>
 *
 * This runs inside {@link
 * io.github.la3679.sentinelflow.api.messaging.consumer.IdempotentEventProcessor}'s transaction,
 * beside the ledger row that records the event as handled. The service's own {@code @Transactional}
 * is {@code REQUIRED} and therefore joins it rather than opening a second one — which is what makes
 * "processed" and "the assessment exists" one fact. It also means the {@link TransactionRecord}
 * loaded below is managed, so its move to {@code ASSESSED} is a dirty-check rather than a second
 * write.
 */
@Component
public class ScoringTransactionCreatedHandler implements TransactionCreatedHandler {

    private static final Logger log = LoggerFactory.getLogger(ScoringTransactionCreatedHandler.class);

    private final TransactionRepository transactions;
    private final RiskAssessmentService assessmentService;

    public ScoringTransactionCreatedHandler(
            TransactionRepository transactions, RiskAssessmentService assessmentService) {
        this.transactions = transactions;
        this.assessmentService = assessmentService;
    }

    @Override
    public void handle(EventEnvelope envelope, TransactionCreatedPayload payload) {
        TransactionRecord transaction = transactions
                .findById(payload.transactionId())
                .orElseThrow(() -> absentTransaction(payload.transactionId()));

        try {
            RiskAssessment assessment = assessmentService.assess(transaction, envelope.correlationId());
            log.debug(
                    "Assessed transaction {} as {} at {}{}",
                    payload.transactionId(),
                    assessment.getRiskBand(),
                    assessment.getFinalScore(),
                    assessment.isDegraded() ? " (degraded: scored by rules alone)" : "");
        } catch (ScoringRejectedException rejected) {
            // NON_RETRYABLE_ERROR rather than SCHEMA_VALIDATION_FAILED: the
            // event this consumer received is valid, and the schema that was
            // not satisfied belongs to a different boundary. An operator
            // reading the dead-letter record needs to look at the scoring
            // contract, not at the producer of this topic.
            throw new NonRetryableEventException(
                    DlqFailureClass.NON_RETRYABLE_ERROR,
                    "Scoring rejected the request for transaction " + payload.transactionId() + " with HTTP "
                            + rejected.status() + "; the two services disagree about "
                            + "contracts/openapi/sentinelflow-scoring.yaml and no retry will change that",
                    rejected);
        }
    }

    /**
     * A {@code transaction.created} naming a transaction that is not in the database.
     *
     * <p>Non-retryable, and the classification is the interesting part. The outbox row is written in
     * the same commit as the transaction it describes (ADR-0005), so an event cannot be published
     * for a row that was never committed — which makes this either a record replayed against a
     * different database or one hand-crafted onto the topic. Neither becomes true on a second
     * attempt, and retrying five times before finding out costs the partition.
     */
    private static NonRetryableEventException absentTransaction(UUID transactionId) {
        return new NonRetryableEventException(
                DlqFailureClass.NON_RETRYABLE_ERROR,
                "No transaction " + transactionId + " exists to assess. The outbox writes an event in the same "
                        + "commit as its transaction, so this record did not come from this database.");
    }
}
