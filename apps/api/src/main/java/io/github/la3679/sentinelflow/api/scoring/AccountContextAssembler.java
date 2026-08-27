/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.scoring;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.github.la3679.sentinelflow.api.domain.Money;
import io.github.la3679.sentinelflow.api.persistence.entity.Account;
import io.github.la3679.sentinelflow.api.persistence.entity.Merchant;
import io.github.la3679.sentinelflow.api.persistence.entity.TransactionRecord;
import io.github.la3679.sentinelflow.api.persistence.repository.AccountHistoryRow;
import io.github.la3679.sentinelflow.api.persistence.repository.AccountRepository;
import io.github.la3679.sentinelflow.api.persistence.repository.MerchantRepository;
import io.github.la3679.sentinelflow.api.persistence.repository.TransactionRepository;
import io.github.la3679.sentinelflow.api.scoring.payload.AccountContext;
import io.github.la3679.sentinelflow.api.scoring.payload.Amount;
import io.github.la3679.sentinelflow.api.scoring.payload.RecentTransaction;
import io.github.la3679.sentinelflow.api.scoring.payload.ScoreRequest;
import io.github.la3679.sentinelflow.api.scoring.payload.TransactionToScore;

/**
 * Builds the request the scoring service is sent — at runtime and at training time, from here and
 * from nowhere else.
 *
 * <p><strong>One implementation is the entire point of this class</strong> (ADR-0010 §1). All
 * sixteen features are computed from the context it produces, so a training-time assembler that
 * windowed, ordered, capped or truncated even slightly differently would produce train/serve skew —
 * and skew of that kind is invisible to every metric in an evaluation report, because both the
 * training and the test halves of the comparison would come from the training assembler and would
 * agree with each other perfectly while disagreeing with production. There is no assertion that
 * catches it after the fact. There is only not writing the second implementation.
 *
 * <p><strong>The balance is a parameter, and that is a deliberate seam rather than an oversight.</strong>
 * {@link #assemble(TransactionRecord)} reads the account's balance, which is the right answer at
 * runtime because scoring happens moments after the transaction. An offline export walking fourteen
 * days of history cannot use it: {@code transactions} records no balance-after column, so a
 * historical balance is not reconstructible from the schema, and today's balance attached to a
 * transaction from eleven days ago would be a confidently wrong number feeding
 * {@code balance_drain_ratio}. So the shared method takes the balance from its caller, which makes
 * supplying a truthful one the export's stated obligation instead of a silent inheritance. The
 * history — the part that is windowed, ordered, capped and leak-prone — is shared.
 *
 * <p><strong>It never looks forward.</strong> The window ends strictly before the scored
 * transaction's own {@code occurredAt}, not before "now". A replayed or late-arriving transaction
 * therefore carries the history it actually had, and the query is what enforces it rather than a
 * convention the next caller has to remember.
 */
@Service
public class AccountContextAssembler {

    /**
     * The shape of {@link AccountContext}. Bumped when a field is added or redefined, and separate
     * from the model and feature versions because what the API can cheaply compute changes for
     * different reasons than what the model wants — a shared version would force both services to
     * deploy together.
     */
    public static final int CONTEXT_VERSION = 1;

    /** Convenience re-export of the contract's cap, so callers need not reach into properties. */
    public static final int MAX_RECENT_TRANSACTIONS = ScoringContextProperties.CONTRACT_MAX_RECENT_TRANSACTIONS;

    private final TransactionRepository transactions;
    private final AccountRepository accounts;
    private final MerchantRepository merchants;
    private final ScoringContextProperties properties;

    public AccountContextAssembler(
            TransactionRepository transactions,
            AccountRepository accounts,
            MerchantRepository merchants,
            ScoringContextProperties properties) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.merchants = merchants;
        this.properties = properties;
    }

    /**
     * The runtime path: everything the scoring service needs about one stored transaction.
     *
     * <p>{@code Propagation.SUPPORTS} rather than {@code REQUIRED}. The consumer calls this from
     * inside the transaction that will also write the assessment (ADR-0008 §1), and joining it is
     * correct; forcing a new one would take a second connection from the pool while the first is
     * still held, which under a backlog is how a pool deadlocks.
     *
     * @throws IllegalStateException if the account or merchant the transaction names is absent. Not
     *     a domain exception with a problem-detail mapping: both are enforced by foreign keys, so an
     *     absence here means the schema is not what the application believes, and the honest
     *     response is to fail rather than to score a transaction whose account cannot be found.
     */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public ScoreRequest assemble(TransactionRecord transaction) {
        Account account = accounts.findById(transaction.getAccountId())
                .orElseThrow(() -> new IllegalStateException("Transaction " + transaction.getId() + " names account "
                        + transaction.getAccountId() + ", which does not exist"));
        Merchant merchant = merchants
                .findById(transaction.getMerchantId())
                .orElseThrow(() -> new IllegalStateException("Transaction " + transaction.getId() + " names merchant "
                        + transaction.getMerchantId() + ", which does not exist"));

        AccountContext context = assembleContext(
                account.getId(), account.getOpenedAt(), account.getBalance(), transaction.getOccurredAt());

        return new ScoreRequest(TransactionToScore.of(transaction, account.getAccountReference(), merchant), context);
    }

    /**
     * The shared core: the account context as it stood immediately before {@code at}.
     *
     * <p>Called directly by the labelled export, which supplies its own balance — see the class
     * comment. Everything else about the context is computed here for both callers.
     *
     * @param accountId whose history to read
     * @param accountOpenedAt account age is a feature
     * @param balance the balance as of {@code at}, which is the caller's to establish
     * @param at the scored transaction's {@code occurredAt}. The window ends strictly before this.
     */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public AccountContext assembleContext(UUID accountId, Instant accountOpenedAt, Money balance, Instant at) {
        long windowSeconds = properties.lookbackWindow().toSeconds();
        int cap = properties.maxRecentTransactions();

        // One more than the cap, so a full page is distinguishable from an
        // overflowing one without a second query over the same window.
        List<AccountHistoryRow> rows = transactions.findAccountHistoryForScoring(
                accountId, at, at.minusSeconds(windowSeconds), Limit.of(cap + 1));

        boolean truncated = rows.size() > cap;
        List<RecentTransaction> recent = (truncated ? rows.subList(0, cap) : rows)
                .stream().map(AccountContextAssembler::toRecentTransaction).toList();

        return new AccountContext(
                CONTEXT_VERSION, windowSeconds, accountOpenedAt, Amount.of(balance), recent, truncated);
    }

    private static RecentTransaction toRecentTransaction(AccountHistoryRow row) {
        return new RecentTransaction(
                row.occurredAt(),
                Amount.of(Money.of(row.amount(), row.currency())),
                row.merchantReference(),
                row.deviceReference(),
                row.originCountry(),
                row.channel(),
                row.type());
    }
}
