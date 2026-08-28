/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.la3679.sentinelflow.api.domain.RiskBand;
import io.github.la3679.sentinelflow.api.persistence.entity.RiskAssessment;
import io.github.la3679.sentinelflow.api.persistence.repository.RiskAssessmentRepository;
import io.github.la3679.sentinelflow.api.persistence.repository.TransactionRepository;
import io.github.la3679.sentinelflow.api.persistence.repository.TransactionSummaryRow;
import io.github.la3679.sentinelflow.api.service.exception.AssessmentNotFoundException;
import io.github.la3679.sentinelflow.api.service.exception.InvalidWindowException;
import io.github.la3679.sentinelflow.api.service.exception.TransactionNotFoundException;

/**
 * Reading transactions, and the decisions behind them.
 *
 * <p>Separate from {@link TransactionIngestionService} because they share nothing but a table:
 * ingestion writes one row and its outbox record in a transaction it owns, and this reads. Putting
 * both behind one service would give the write path's transactional boundary to a query.
 *
 * <p>Read-only transactions throughout, so a page and the count describing it come from one
 * snapshot. Without that, a row written between the two queries makes {@code totalElements} disagree
 * with what a caller can actually reach.
 */
@Service
public class TransactionQueryService {

    private final TransactionRepository transactions;
    private final RiskAssessmentRepository assessments;

    public TransactionQueryService(TransactionRepository transactions, RiskAssessmentRepository assessments) {
        this.transactions = transactions;
        this.assessments = assessments;
    }

    /**
     * One page of transactions, newest first.
     *
     * @throws InvalidWindowException if the window ends before it starts. Refused rather than
     *     silently answered with nothing: an empty page and an impossible question look identical to
     *     a caller, and only one of them is their mistake.
     */
    @Transactional(readOnly = true)
    public Page<TransactionSummaryRow> list(
            String accountReference,
            RiskBand riskBand,
            Instant occurredAfter,
            Instant occurredBefore,
            Pageable pageable) {

        if (occurredAfter != null && occurredBefore != null && !occurredBefore.isAfter(occurredAfter)) {
            throw new InvalidWindowException("occurredBefore must be after occurredAfter");
        }
        return transactions.findReadablePage(accountReference, riskBand, occurredAfter, occurredBefore, pageable);
    }

    /**
     * One transaction.
     *
     * @throws TransactionNotFoundException if nothing has that identifier
     */
    @Transactional(readOnly = true)
    public TransactionSummaryRow get(UUID transactionId) {
        return transactions
                .findReadableById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
    }

    /**
     * The current assessment behind a transaction, which is the highest version.
     *
     * <p>Both failures are checked, and they are different questions: a transaction that does not
     * exist is the caller's mistake, and one that exists without an assessment is this system still
     * working. The contract answers both {@code 404} because a client polling for the assessment
     * acts identically either way, but the log line does not have to be as vague as the status code.
     *
     * @throws TransactionNotFoundException if nothing has that identifier
     * @throws AssessmentNotFoundException if the transaction exists and nothing has scored it yet
     */
    @Transactional(readOnly = true)
    public RiskAssessment assessmentFor(UUID transactionId) {
        if (!transactions.existsById(transactionId)) {
            throw new TransactionNotFoundException(transactionId);
        }
        return assessments
                .findFirstByTransactionIdOrderByAssessmentVersionDesc(transactionId)
                .orElseThrow(() -> new AssessmentNotFoundException(transactionId));
    }
}
