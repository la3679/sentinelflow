/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import io.github.la3679.sentinelflow.api.domain.AccountStatus;
import io.github.la3679.sentinelflow.api.domain.IngestionSource;
import io.github.la3679.sentinelflow.api.domain.Money;
import io.github.la3679.sentinelflow.api.domain.TransactionChannel;
import io.github.la3679.sentinelflow.api.domain.TransactionType;
import io.github.la3679.sentinelflow.api.persistence.entity.Account;
import io.github.la3679.sentinelflow.api.persistence.entity.Merchant;
import io.github.la3679.sentinelflow.api.persistence.entity.TransactionRecord;
import io.github.la3679.sentinelflow.api.persistence.repository.AccountRepository;
import io.github.la3679.sentinelflow.api.persistence.repository.MerchantRepository;
import io.github.la3679.sentinelflow.api.persistence.repository.TransactionRepository;
import io.github.la3679.sentinelflow.api.scoring.payload.AccountContext;
import io.github.la3679.sentinelflow.api.scoring.payload.RecentTransaction;
import io.github.la3679.sentinelflow.api.scoring.payload.ScoreRequest;
import io.github.la3679.sentinelflow.api.support.AbstractPostgresTest;
import io.github.la3679.sentinelflow.api.support.SchemaFixtures;

/**
 * The assembler against real PostgreSQL, because every property worth asserting here is a property
 * of a query.
 *
 * <p>These are not tests of "does it return something". Each one names a way the context could be
 * quietly wrong in a manner the scoring service could not detect and no metric would reveal — a row
 * from after the scored instant, a window that reaches too far, a truncation that keeps different
 * rows on different runs. ADR-0010 §1 makes this assembler the single implementation behind both
 * training and serving, so a defect here is a defect in every score the system will produce
 * <em>and</em> in every number in its evaluation report, simultaneously and consistently — which is
 * precisely the class of defect that no comparison between the two can reveal.
 *
 * <p><strong>The cap is lowered to five here.</strong> Asserting truncation at the real cap of 200
 * would mean inserting several hundred rows three times over to prove arithmetic that does not
 * depend on the number. The cap's real value is pinned to the contract's {@code maxItems} by
 * {@code ScoringPayloadContractIT}, and clamping is covered by {@code ScoringContextPropertiesTests}
 * — so the boundary is tested where it is cheap and the value is tested where it matters.
 */
@TestPropertySource(
        properties = {
            "sentinelflow.scoring.context.max-recent-transactions=5",
            "sentinelflow.scoring.context.lookback-window=1h"
        })
class AccountContextAssemblerIT extends AbstractPostgresTest {

    private static final Instant SCORED_AT = Instant.parse("2026-08-20T12:00:00Z");
    private static final Instant OPENED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final String CURRENCY = "GBP";

    @Autowired
    private AccountContextAssembler assembler;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private MerchantRepository merchants;

    @Autowired
    private ScoringContextProperties properties;

    @Autowired
    private JdbcTemplate jdbc;

    // ----------------------------------------------------------------------- //
    // The window
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("history at or after the scored instant is excluded, because the API asks later than it happened")
    void neverLooksForward() {
        Account account = anAccount();
        Merchant merchant = aMerchant();

        transactionAt(account, merchant, SCORED_AT.minus(Duration.ofMinutes(30)), "10.00");
        transactionAt(account, merchant, SCORED_AT, "20.00");
        transactionAt(account, merchant, SCORED_AT.plus(Duration.ofMinutes(30)), "30.00");

        AccountContext context = contextFor(account);

        assertThat(context.recentTransactions())
                .as("a replayed or late-arriving transaction legitimately carries history from after "
                        + "itself, and a feature computed over that is a model being shown the future")
                .extracting(recent -> recent.amount().value())
                .containsExactly("10.0000");
    }

    @Test
    @DisplayName("history older than the lookback window is excluded")
    void staysInsideTheWindow() {
        Account account = anAccount();
        Merchant merchant = aMerchant();

        long window = properties.lookbackWindow().toSeconds();
        transactionAt(account, merchant, SCORED_AT.minusSeconds(window - 60), "10.00");
        transactionAt(account, merchant, SCORED_AT.minusSeconds(window + 60), "20.00");

        AccountContext context = contextFor(account);

        assertThat(context.recentTransactions())
                .extracting(recent -> recent.amount().value())
                .containsExactly("10.0000");
        assertThat(context.lookbackWindowSeconds())
                .as("stated rather than assumed: a 24-hour feature given an hour of history is not "
                        + "a smaller number, it is a number meaning something other than its name")
                .isEqualTo(window);
    }

    @Test
    @DisplayName("a transaction exactly at the window edge is included")
    void includesTheWindowEdge() {
        Account account = anAccount();
        Merchant merchant = aMerchant();

        long window = properties.lookbackWindow().toSeconds();
        transactionAt(account, merchant, SCORED_AT.minusSeconds(window), "10.00");

        assertThat(contextFor(account).recentTransactions())
                .as("the window is inclusive at its far edge and exclusive at the scored instant; "
                        + "both halves are asserted so neither drifts into the other")
                .hasSize(1);
    }

    @Test
    @DisplayName("another account's history never appears")
    void isScopedToOneAccount() {
        Account account = anAccount();
        Account other = anAccount();
        Merchant merchant = aMerchant();

        transactionAt(account, merchant, SCORED_AT.minus(Duration.ofMinutes(5)), "10.00");
        transactionAt(other, merchant, SCORED_AT.minus(Duration.ofMinutes(5)), "999.00");

        assertThat(contextFor(account).recentTransactions())
                .extracting(recent -> recent.amount().value())
                .containsExactly("10.0000");
    }

    // ----------------------------------------------------------------------- //
    // Ordering and truncation
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("recentTransactions is newest first, which the scoring service validates rather than sorts")
    void isNewestFirst() {
        Account account = anAccount();
        Merchant merchant = aMerchant();

        transactionAt(account, merchant, SCORED_AT.minus(Duration.ofMinutes(30)), "30.00");
        transactionAt(account, merchant, SCORED_AT.minus(Duration.ofMinutes(10)), "10.00");
        transactionAt(account, merchant, SCORED_AT.minus(Duration.ofMinutes(20)), "20.00");

        assertThat(contextFor(account).recentTransactions())
                .as("the Python side rejects a mis-ordered list instead of re-sorting it, because a "
                        + "silent re-sort would hide a caller sending an order it did not mean to")
                .extracting(recent -> recent.amount().value())
                .containsExactly("10.0000", "20.0000", "30.0000");
    }

    @Test
    @DisplayName("exactly the cap is not reported as truncated")
    void doesNotClaimTruncationAtExactlyTheCap() {
        Account account = anAccount();
        Merchant merchant = aMerchant();

        int cap = properties.maxRecentTransactions();
        for (int i = 1; i <= cap; i++) {
            transactionAt(account, merchant, SCORED_AT.minusSeconds(i), "1.00");
        }

        AccountContext context = contextFor(account);

        assertThat(context.recentTransactions()).hasSize(cap);
        assertThat(context.truncated())
                .as("exactly the cap is a complete answer; claiming truncation would turn every "
                        + "count downstream into a floor when it is in fact exact")
                .isFalse();
    }

    @Test
    @DisplayName("over the cap keeps the newest rows and says it truncated")
    void truncatesToTheCapAndSaysSo() {
        Account account = anAccount();
        Merchant merchant = aMerchant();

        int cap = properties.maxRecentTransactions();
        for (int i = 1; i <= cap + 5; i++) {
            transactionAt(account, merchant, SCORED_AT.minusSeconds(i), "1.00");
        }

        AccountContext context = contextFor(account);

        assertThat(context.recentTransactions()).hasSize(cap);
        assertThat(context.truncated())
                .as("a count computed from a truncated list is a floor rather than a count, and the "
                        + "scoring service can only say so if it is told")
                .isTrue();
        assertThat(context.recentTransactions().getFirst().occurredAt())
                .as("the newest are kept: dropping recent history would break every velocity feature")
                .isEqualTo(SCORED_AT.minusSeconds(1));
    }

    @Test
    @DisplayName("truncation keeps the same rows every time, even when every timestamp ties")
    void truncationIsDeterministicUnderTies() {
        Account account = anAccount();
        Merchant merchant = aMerchant();

        // One instant for every row, so occurredAt alone cannot order them and
        // the tiebreaker is the only thing deciding which survive the cap.
        Instant tied = SCORED_AT.minus(Duration.ofMinutes(1));
        int cap = properties.maxRecentTransactions();
        for (int i = 0; i < cap + 5; i++) {
            transactionAt(account, merchant, tied, "1.00");
        }

        List<RecentTransaction> first = contextFor(account).recentTransactions();
        List<RecentTransaction> second = contextFor(account).recentTransactions();

        assertThat(first).hasSize(cap);
        assertThat(first)
                .as("without a tiebreaker the surviving rows vary between plans, so the same "
                        + "transaction would score differently on a retry — an unreproducible score "
                        + "nobody would think to blame on an ORDER BY")
                .isEqualTo(second);
    }

    // ----------------------------------------------------------------------- //
    // What the rows carry
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("the merchant reference comes from the join, not from the transaction row")
    void resolvesTheMerchantReference() {
        Account account = anAccount();
        Merchant merchant = aMerchant();

        transactionAt(account, merchant, SCORED_AT.minus(Duration.ofMinutes(5)), "10.00");

        assertThat(contextFor(account).recentTransactions())
                .singleElement()
                .extracting(RecentTransaction::merchantReference)
                .as("transactions holds merchant_id; the new-merchant feature compares references")
                .isEqualTo(merchant.getMerchantReference());
    }

    @Test
    @DisplayName("a deviceless channel carries a null device rather than an omitted field")
    void carriesNullDevices() {
        Account account = anAccount();
        Merchant merchant = aMerchant();

        transactions.saveAndFlush(new TransactionRecord(
                SchemaFixtures.nextTransactionReference(jdbc),
                "idem-" + UUID.randomUUID(),
                account.getId(),
                merchant.getId(),
                TransactionType.WITHDRAWAL,
                TransactionChannel.ATM,
                Money.of(new BigDecimal("40.00"), CURRENCY),
                "GB",
                null,
                SCORED_AT.minus(Duration.ofMinutes(5)),
                IngestionSource.API,
                UUID.randomUUID()));

        assertThat(contextFor(account).recentTransactions())
                .singleElement()
                .extracting(RecentTransaction::deviceReference)
                .as("an ATM withdrawal genuinely has no device, and reading that as a new device "
                        + "would make every cash withdrawal look novel")
                .isNull();
    }

    @Test
    @DisplayName("an account with no history is an empty list, not a failure")
    void handlesAnAccountWithNoHistory() {
        Account account = anAccount();

        AccountContext context = contextFor(account);

        assertThat(context.recentTransactions())
                .as("a service that refused to answer when history was thin would be unavailable "
                        + "exactly when an account is new, which is when a score matters most")
                .isEmpty();
        assertThat(context.truncated()).isFalse();
        assertThat(context.contextVersion()).isEqualTo(AccountContextAssembler.CONTEXT_VERSION);
    }

    @Test
    @DisplayName("the balance is the caller's, stated as a decimal string with its currency")
    void carriesTheSuppliedBalance() {
        Account account = anAccount();

        AccountContext context = assembler.assembleContext(
                account.getId(), account.getOpenedAt(), Money.of(new BigDecimal("1234.5600"), CURRENCY), SCORED_AT);

        assertThat(context.currentBalance().value())
                .as("never a JSON number: JSON.parse would round it before any consumer saw it")
                .isEqualTo("1234.5600");
        assertThat(context.currentBalance().currency()).isEqualTo(CURRENCY);
    }

    // ----------------------------------------------------------------------- //
    // The runtime path
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("assembling from a stored transaction fills both halves of the request")
    void assemblesAWholeRequest() {
        Account account = anAccount();
        Merchant merchant = aMerchant();

        transactionAt(account, merchant, SCORED_AT.minus(Duration.ofMinutes(5)), "10.00");
        TransactionRecord scored = transactionAt(account, merchant, SCORED_AT, "250.00");

        ScoreRequest request = assembler.assemble(scored);

        assertThat(request.transaction().transactionId()).isEqualTo(scored.getId());
        assertThat(request.transaction().accountReference()).isEqualTo(account.getAccountReference());
        assertThat(request.transaction().merchantReference()).isEqualTo(merchant.getMerchantReference());
        assertThat(request.transaction().merchantCategoryCode()).isEqualTo(merchant.getCategoryCode());
        assertThat(request.transaction().amount().value()).isEqualTo("250.0000");
        assertThat(request.transaction().occurredAt()).isEqualTo(SCORED_AT);

        assertThat(request.accountContext().accountOpenedAt()).isEqualTo(account.getOpenedAt());
        assertThat(request.accountContext().currentBalance().value())
                .as("the runtime path reads the account's balance, which is the right answer because "
                        + "scoring happens moments after the transaction")
                .isEqualTo("5000.0000");
        assertThat(request.accountContext().recentTransactions())
                .as("the scored transaction is excluded from its own history by the strict <")
                .extracting(recent -> recent.amount().value())
                .containsExactly("10.0000");
    }

    @Test
    @DisplayName("a transaction naming an account that does not exist fails rather than scoring blind")
    void failsWhenTheAccountIsMissing() {
        Merchant merchant = aMerchant();

        // Never persisted: the foreign key makes this state unreachable through
        // the schema, which is exactly why the assembler must not assume it away.
        TransactionRecord orphan = new TransactionRecord(
                SchemaFixtures.nextTransactionReference(jdbc),
                "idem-" + UUID.randomUUID(),
                UUID.randomUUID(),
                merchant.getId(),
                TransactionType.PURCHASE,
                TransactionChannel.CARD_NOT_PRESENT,
                Money.of(new BigDecimal("10.00"), CURRENCY),
                "GB",
                "DEV-0123456789ab",
                SCORED_AT,
                IngestionSource.API,
                UUID.randomUUID());

        assertThatThrownBy(() -> assembler.assemble(orphan))
                .as("an absent account means the schema is not what the application believes, and "
                        + "scoring anyway would be answering about a transaction whose account "
                        + "cannot be found")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not exist");
    }

    // ----------------------------------------------------------------------- //

    private AccountContext contextFor(Account account) {
        return assembler.assembleContext(account.getId(), account.getOpenedAt(), account.getBalance(), SCORED_AT);
    }

    private Account anAccount() {
        UUID customerId = jdbc.queryForObject("""
                INSERT INTO customers (customer_reference, country_code, risk_tier, status)
                VALUES (?, 'GB', 'STANDARD', 'ACTIVE')
                RETURNING id
                """, UUID.class, "CUS-" + SchemaFixtures.next6());

        return accounts.saveAndFlush(new Account(
                customerId,
                "ACC-" + SchemaFixtures.next6(),
                Money.of(new BigDecimal("5000.00"), CURRENCY),
                AccountStatus.ACTIVE,
                OPENED_AT));
    }

    private Merchant aMerchant() {
        return merchants.saveAndFlush(
                new Merchant("MER-" + SchemaFixtures.next4(), "Synthetic Supplies", "5411", "GB"));
    }

    private TransactionRecord transactionAt(Account account, Merchant merchant, Instant at, String amount) {
        return transactions.saveAndFlush(new TransactionRecord(
                SchemaFixtures.nextTransactionReference(jdbc),
                "idem-" + UUID.randomUUID(),
                account.getId(),
                merchant.getId(),
                TransactionType.PURCHASE,
                TransactionChannel.CARD_NOT_PRESENT,
                Money.of(new BigDecimal(amount), CURRENCY),
                "GB",
                "DEV-0123456789ab",
                at,
                IngestionSource.API,
                UUID.randomUUID()));
    }
}
