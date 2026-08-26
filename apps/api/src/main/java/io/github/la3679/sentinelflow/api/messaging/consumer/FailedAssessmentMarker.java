/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.messaging.consumer;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.github.la3679.sentinelflow.api.persistence.repository.TransactionRepository;

/**
 * Records that a dead-lettered transaction will never be assessed.
 *
 * <p>A transaction whose {@code transaction.created} event could not be processed is not waiting for
 * an assessment that is merely late; it is waiting for one that is not coming. Leaving it
 * {@code PENDING} would make the console quietly wrong about it for ever, and would make "how many
 * transactions are awaiting assessment" a number that only ever grows.
 *
 * <p>Its own component, and its own transaction, because the recoverer runs on the listener thread
 * outside any transaction. Spring's proxying means a private method here would not open one, and a
 * database write with no transaction boundary is the sort of thing that works until it matters.
 */
@Component
public class FailedAssessmentMarker {

    private static final Logger log = LoggerFactory.getLogger(FailedAssessmentMarker.class);

    private final TransactionRepository transactions;

    public FailedAssessmentMarker(TransactionRepository transactions) {
        this.transactions = transactions;
    }

    /**
     * @param transactionId the aggregate the dead-lettered event was about. A row that does not
     *     exist, or one that has since been assessed, is not an error: the guard in the query is what
     *     makes a second dead-letter record harmless.
     */
    @Transactional
    public void mark(UUID transactionId) {
        if (transactions.markProcessingFailed(transactionId) == 0) {
            log.debug("Transaction {} was not marked failed; it is absent or no longer pending", transactionId);
        }
    }
}
