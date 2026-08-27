/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.la3679.sentinelflow.api.domain.AccountStatus;
import io.github.la3679.sentinelflow.api.domain.AlertPriority;
import io.github.la3679.sentinelflow.api.domain.AlertStatus;
import io.github.la3679.sentinelflow.api.domain.CustomerStatus;
import io.github.la3679.sentinelflow.api.domain.EventType;
import io.github.la3679.sentinelflow.api.domain.IngestionSource;
import io.github.la3679.sentinelflow.api.domain.Money;
import io.github.la3679.sentinelflow.api.domain.ReasonCode;
import io.github.la3679.sentinelflow.api.domain.ReasonSource;
import io.github.la3679.sentinelflow.api.domain.RiskBand;
import io.github.la3679.sentinelflow.api.domain.RiskTier;
import io.github.la3679.sentinelflow.api.domain.TransactionChannel;
import io.github.la3679.sentinelflow.api.domain.TransactionType;
import io.github.la3679.sentinelflow.api.persistence.entity.Account;
import io.github.la3679.sentinelflow.api.persistence.entity.Alert;
import io.github.la3679.sentinelflow.api.persistence.entity.Customer;
import io.github.la3679.sentinelflow.api.persistence.entity.Merchant;
import io.github.la3679.sentinelflow.api.persistence.entity.OutboxEvent;
import io.github.la3679.sentinelflow.api.persistence.entity.RiskAssessment;
import io.github.la3679.sentinelflow.api.persistence.entity.TransactionRecord;
import io.github.la3679.sentinelflow.api.support.AbstractPostgresTest;
import io.github.la3679.sentinelflow.api.support.SchemaFixtures;

/**
 * What the mappings do to a value on the way to the database and back.
 *
 * <p>{@code ddl-auto: validate} proves a column exists with a compatible type. It says nothing
 * about whether an amount survives the round trip at the right scale, whether an enum reaches the
 * column in the spelling the contract requires, or whether the optimistic lock actually fires. Each
 * of those is a defect that passes every mapping check and shows up in production.
 */
class EntityMappingIT extends AbstractPostgresTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactions;

    private Account persistedAccount() {
        Customer customer =
                new Customer("CUS-" + SchemaFixtures.next6(), "GB", RiskTier.STANDARD, CustomerStatus.ACTIVE);
        Account account = new Account(
                customer.getId(),
                "ACC-" + SchemaFixtures.next6(),
                Money.of("1000.00", "GBP"),
                AccountStatus.ACTIVE,
                Instant.now());
        entityManager.persist(customer);
        entityManager.persist(account);
        return account;
    }

    private Merchant persistedMerchant() {
        Merchant merchant = new Merchant("MER-" + SchemaFixtures.next4(), "Synthetic Supplies", "5411", "GB");
        entityManager.persist(merchant);
        return merchant;
    }

    @Test
    @Transactional
    @DisplayName("an amount comes back equal to the amount that went in")
    void moneySurvivesTheRoundTrip() {
        Account account = persistedAccount();
        Merchant merchant = persistedMerchant();

        // 1.50 stored in NUMERIC(19,4) reads back as 1.5000. BigDecimal.equals
        // compares scale, so this is exactly the comparison that fails when
        // Money is written with equals instead of compareTo.
        TransactionRecord transaction = new TransactionRecord(
                "TXN-" + SchemaFixtures.next6(),
                "idem-" + SchemaFixtures.next6(),
                account.getId(),
                merchant.getId(),
                TransactionType.PURCHASE,
                TransactionChannel.CARD_NOT_PRESENT,
                Money.of("1.50", "GBP"),
                "GB",
                null,
                Instant.now(),
                IngestionSource.API,
                UUID.randomUUID());
        entityManager.persist(transaction);
        entityManager.flush();
        entityManager.clear();

        TransactionRecord loaded = entityManager.find(TransactionRecord.class, transaction.getId());
        assertThat(loaded.getMoney()).isEqualTo(Money.of("1.50", "GBP"));
        assertThat(loaded.getMoney().amount()).hasScaleOf(4);
        assertThat(loaded.getMoney().toPlainString()).isEqualTo("1.5000");
    }

    @Test
    @Transactional
    @DisplayName("an account refuses a balance in another currency")
    void accountKeepsItsDenomination() {
        Account account = persistedAccount();

        assertThatThrownBy(() -> account.setBalance(Money.of("10.00", "EUR")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GBP");
    }

    @Test
    @Transactional
    @DisplayName("reason codes round-trip through jsonb as a list, not as a string")
    void reasonCodesRoundTripAsJson() {
        Account account = persistedAccount();
        Merchant merchant = persistedMerchant();
        TransactionRecord transaction = new TransactionRecord(
                "TXN-" + SchemaFixtures.next6(),
                "idem-" + SchemaFixtures.next6(),
                account.getId(),
                merchant.getId(),
                TransactionType.PURCHASE,
                TransactionChannel.ONLINE_TRANSFER,
                Money.of("500.00", "GBP"),
                "GB",
                null,
                Instant.now(),
                IngestionSource.GENERATOR,
                UUID.randomUUID());
        entityManager.persist(transaction);

        RiskAssessment assessment = RiskAssessment.scored(
                transaction.getId(),
                1,
                new BigDecimal("40.00"),
                new BigDecimal("62.50"),
                new BigDecimal("55.25"),
                RiskBand.HIGH,
                "1.0.0",
                "1.0.0",
                "1.0.0",
                List.of(
                        new ReasonCode(
                                "VELOCITY_5M_HIGH",
                                "4 transactions in the five minutes before it",
                                new BigDecimal("25"),
                                ReasonSource.RULE),
                        new ReasonCode(
                                "AMOUNT_RATIO_HIGH",
                                "the amount is 9.4 times this account's own recent mean",
                                new BigDecimal("1.8231"),
                                ReasonSource.MODEL)),
                12,
                true,
                Instant.now());
        entityManager.persist(assessment);
        entityManager.flush();
        entityManager.clear();

        RiskAssessment loaded = entityManager.find(RiskAssessment.class, assessment.getId());
        assertThat(loaded.getReasonCodes())
                .as("objects, not bare codes: both contracts have always described a code, a "
                        + "description, a contribution and a source")
                .extracting(ReasonCode::code)
                .containsExactly("VELOCITY_5M_HIGH", "AMOUNT_RATIO_HIGH");
        assertThat(loaded.getReasonCodes())
                .extracting(ReasonCode::source)
                .as("an analyst needs to know which: a rule can be read, a model score can only " + "be attributed")
                .containsExactly(ReasonSource.RULE, ReasonSource.MODEL);
        assertThat(loaded.getReasonCodes().getFirst().contribution())
                .as("a rule's reasons sum to the rule score, which is the property a ruleset has "
                        + "and a model does not")
                .isEqualByComparingTo("25");
        assertThat(loaded.getModelScore()).isEqualByComparingTo("62.50");
        assertThat(loaded.isDegraded()).isFalse();
    }

    @Test
    @Transactional
    @DisplayName("a degraded assessment is unconstructible with model output")
    void degradedFactoryOmitsModelFields() {
        Account account = persistedAccount();
        Merchant merchant = persistedMerchant();
        TransactionRecord transaction = new TransactionRecord(
                "TXN-" + SchemaFixtures.next6(),
                "idem-" + SchemaFixtures.next6(),
                account.getId(),
                merchant.getId(),
                TransactionType.WITHDRAWAL,
                TransactionChannel.ATM,
                Money.of("80.00", "GBP"),
                "GB",
                null,
                Instant.now(),
                IngestionSource.SCENARIO_REPLAY,
                UUID.randomUUID());
        entityManager.persist(transaction);

        RiskAssessment assessment = RiskAssessment.degraded(
                transaction.getId(),
                1,
                new BigDecimal("70.00"),
                new BigDecimal("70.00"),
                RiskBand.HIGH,
                "1.0.0",
                List.of(new ReasonCode(
                        "SCORING_UNAVAILABLE",
                        "The scoring service did not answer within its budget; scored by rules alone.",
                        java.math.BigDecimal.ZERO,
                        ReasonSource.RULE)),
                true,
                Instant.now());
        entityManager.persist(assessment);

        // The CHECK accepts this only because every model-derived field is
        // absent. A zero would be a claim about the transaction that nobody
        // made.
        entityManager.flush();
        entityManager.clear();

        RiskAssessment loaded = entityManager.find(RiskAssessment.class, assessment.getId());
        assertThat(loaded.isDegraded()).isTrue();
        assertThat(loaded.getModelScore()).isNull();
        assertThat(loaded.getModelVersion()).isNull();
        assertThat(loaded.getScoringLatencyMs()).isZero();
    }

    @Test
    @DisplayName("a stale write loses to the analyst who got there first")
    void alertOptimisticLockFires() {
        UUID alertId = transactions.execute(status -> {
            Account account = persistedAccount();
            Merchant merchant = persistedMerchant();
            TransactionRecord transaction = new TransactionRecord(
                    "TXN-" + SchemaFixtures.next6(),
                    "idem-" + SchemaFixtures.next6(),
                    account.getId(),
                    merchant.getId(),
                    TransactionType.PURCHASE,
                    TransactionChannel.CARD_NOT_PRESENT,
                    Money.of("900.00", "GBP"),
                    "GB",
                    null,
                    Instant.now(),
                    IngestionSource.API,
                    UUID.randomUUID());
            entityManager.persist(transaction);
            RiskAssessment assessment = RiskAssessment.degraded(
                    transaction.getId(),
                    1,
                    new BigDecimal("90.00"),
                    new BigDecimal("90.00"),
                    RiskBand.CRITICAL,
                    "1.0.0",
                    List.of(new ReasonCode(
                            "SCORING_UNAVAILABLE",
                            "The scoring service did not answer within its budget; scored by rules alone.",
                            java.math.BigDecimal.ZERO,
                            ReasonSource.RULE)),
                    true,
                    Instant.now());
            entityManager.persist(assessment);
            Alert alert = new Alert(
                    "ALT-" + SchemaFixtures.next4(),
                    transaction.getId(),
                    assessment.getId(),
                    AlertPriority.URGENT,
                    "Synthetic alert",
                    RiskBand.CRITICAL,
                    new BigDecimal("90.00"));
            entityManager.persist(alert);
            return alert.getId();
        });

        // Analyst A opens the alert. A new alert is at version 0, which is what
        // the amended OpenAPI contract says and what a client echoes back as
        // expectedVersion.
        Alert asAnalystARead = transactions.execute(status -> {
            Alert alert = entityManager.find(Alert.class, alertId);
            entityManager.detach(alert);
            return alert;
        });
        assertThat(asAnalystARead.getVersion()).isZero();

        // Analyst B acts first and wins. The row moves to version 1.
        transactions.executeWithoutResult(
                status -> entityManager.find(Alert.class, alertId).setPriority(AlertPriority.HIGH));

        // Analyst A now submits against what they read. The UPDATE carries
        // WHERE version = 0, matches nothing, and the request is told rather
        // than silently overwriting analyst B.
        //
        // The JPA exception, not Spring's ObjectOptimisticLockingFailureException:
        // translation happens in a @Repository proxy, and this test drives the
        // EntityManager directly. Asserting the translated type here would be
        // asserting something this code path does not do.
        asAnalystARead.setSummary("Escalating after review");
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                    entityManager.merge(asAnalystARead);
                    entityManager.flush();
                }))
                .isInstanceOf(OptimisticLockException.class)
                .hasMessageContaining("already updated or deleted by another transaction");

        // And the winner's change is still there.
        long finalVersion = transactions.execute(
                status -> entityManager.find(Alert.class, alertId).getVersion());
        assertThat(finalVersion).isEqualTo(1L);
    }

    @Test
    @Transactional
    @DisplayName("a terminal transition stamps the close time the schema demands")
    void transitionSetsCloseTime() {
        Alert alert = new Alert(
                "ALT-" + SchemaFixtures.next4(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                AlertPriority.LOW,
                "Synthetic",
                RiskBand.LOW,
                new BigDecimal("10.00"));

        assertThat(alert.getStatus()).isEqualTo(AlertStatus.NEW);
        assertThat(alert.getClosedAt()).isNull();
        assertThat(alert.isTerminal()).isFalse();

        Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        alert.transitionTo(AlertStatus.DISMISSED_FALSE_POSITIVE, at);

        assertThat(alert.isTerminal()).isTrue();
        assertThat(alert.getClosedAt()).isEqualTo(at);

        // Reopening clears it again, because the CHECK forbids a live alert
        // that still remembers when it closed.
        alert.transitionTo(AlertStatus.IN_REVIEW, Instant.now());
        assertThat(alert.getClosedAt()).isNull();
    }

    @Test
    @Transactional
    @DisplayName("an event type reaches the column in the contract's spelling, not Java's")
    void eventTypeIsStoredAsTheContractSpellsIt() {
        OutboxEvent event = new OutboxEvent(
                EventType.TRANSACTION_CREATED,
                UUID.randomUUID(),
                1,
                "ACC-000001",
                "{}",
                UUID.randomUUID(),
                null,
                Instant.now());
        entityManager.persist(event);
        entityManager.flush();

        // Read back as raw SQL: a consumer matching the envelope it received
        // against this column sees transaction.created, never
        // TRANSACTION_CREATED.
        Object[] stored = (Object[]) entityManager
                .createNativeQuery("SELECT event_type, aggregate_type FROM outbox_events WHERE id = :id")
                .setParameter("id", event.getId())
                .getSingleResult();

        assertThat(stored[0]).isEqualTo("transaction.created");
        assertThat(stored[1]).isEqualTo("transaction");

        entityManager.clear();
        OutboxEvent loaded = entityManager.find(OutboxEvent.class, event.getId());
        assertThat(loaded.getEventType()).isEqualTo(EventType.TRANSACTION_CREATED);
    }

    @Test
    @Transactional
    @DisplayName("identifiers are UUIDv7, so a primary key index stays append-ordered")
    void identifiersAreTimeOrdered() {
        Merchant first = persistedMerchant();
        Merchant second = persistedMerchant();

        assertThat(first.getId().version()).isEqualTo(7);
        assertThat(second.getId().version()).isEqualTo(7);
        // Monotonic within the process, which is the property that keeps
        // inserts landing at the right-hand edge of the index (ADR-0007).
        assertThat(first.getId().toString()).isLessThan(second.getId().toString());
    }
}
