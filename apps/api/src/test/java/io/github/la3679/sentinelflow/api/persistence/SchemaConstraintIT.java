/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.la3679.sentinelflow.api.support.AbstractPostgresTest;
import io.github.la3679.sentinelflow.api.support.SchemaFixtures;

/**
 * Proves the schema <em>rejects</em> what it is supposed to reject.
 *
 * <p><strong>Why this suite exists at all.</strong> A migration test that only checks the migration
 * applied has tested Flyway, not the schema. Every constraint below encodes an invariant that
 * application code will otherwise get wrong eventually, and the only evidence that the constraint
 * works is a write that the database refuses.
 *
 * <p>Each test names the constraint it expects to fire. Asserting merely that "something failed"
 * would let a test pass because of a typo in the fixture, a missing not-null, or a foreign key
 * nobody meant to trip - which is how a constraint quietly stops being tested while its test stays
 * green.
 *
 * <p>These go through {@link JdbcTemplate}, not JPA. The subject is the schema; putting Hibernate
 * in between would make a mapping bug indistinguishable from a constraint bug.
 */
class SchemaConstraintIT extends AbstractPostgresTest {

    @Autowired
    private JdbcTemplate jdbc;

    private SchemaFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new SchemaFixtures(jdbc);
    }

    private void assertViolates(String constraintName, Runnable write) {
        assertThatThrownBy(write::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraintName);
    }

    @Nested
    @DisplayName("identity and reference data (V1)")
    class IdentityAndReferenceData {

        @Test
        @DisplayName("a username that differs only by case cannot impersonate another")
        void usernameFormatIsEnforced() {
            // Upper case, a leading digit and a trailing space are each an
            // impersonation vector, and the cheapest place to make them
            // impossible is the column.
            assertViolates(
                    "users_username_format",
                    () -> jdbc.update(
                            "INSERT INTO users (username, display_name, status) VALUES (?, ?, 'ACTIVE')",
                            "System",
                            "Not the system principal"));
        }

        @Test
        @DisplayName("a role outside the fixed set is refused")
        void unknownRoleCodeIsRefused() {
            assertViolates(
                    "roles_code_known",
                    () -> jdbc.update("INSERT INTO roles (code, description) VALUES ('SUPERUSER', 'Nope')"));
        }

        @Test
        @DisplayName("the system principal exists, so an automated action always has an actor")
        void systemPrincipalIsPresent() {
            assertThat(fixtures.systemUserId()).isNotNull();
            Integer roleCount = jdbc.queryForObject("SELECT count(*) FROM roles", Integer.class);
            assertThat(roleCount).isEqualTo(4);
        }

        @Test
        @DisplayName("deleting a user removes the grants that only described them")
        void roleGrantsCascadeWithTheirUser() {
            UUID userId = jdbc.queryForObject(
                    "INSERT INTO users (username, display_name, status) VALUES (?, 'Temp', 'ACTIVE') RETURNING id",
                    UUID.class,
                    "temp" + SchemaFixtures.next6());
            jdbc.update(
                    "INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = 'ANALYST'", userId);

            jdbc.update("DELETE FROM users WHERE id = ?", userId);

            Integer remaining =
                    jdbc.queryForObject("SELECT count(*) FROM user_roles WHERE user_id = ?", Integer.class, userId);
            assertThat(remaining).isZero();
        }
    }

    @Nested
    @DisplayName("parties and accounts (V2)")
    class PartiesAndAccounts {

        @Test
        @DisplayName("a malformed customer reference is refused")
        void customerReferenceFormatIsEnforced() {
            assertViolates("customers_reference_format", () -> jdbc.update("""
                            INSERT INTO customers (customer_reference, country_code, risk_tier, status)
                            VALUES ('CUS-12', 'GB', 'STANDARD', 'ACTIVE')
                            """));
        }

        @Test
        @DisplayName("a customer with accounts cannot be deleted out from under them")
        void accountForeignKeyRestricts() {
            UUID customerId = fixtures.insertCustomer();
            fixtures.insertAccount(customerId);

            assertViolates("accounts_customer_fk", () -> jdbc.update("DELETE FROM customers WHERE id = ?", customerId));
        }

        @Test
        @DisplayName("a merchant category code keeps its leading zero and its length")
        void merchantCategoryFormatIsEnforced() {
            assertViolates("merchants_category_format", () -> jdbc.update("""
                            INSERT INTO merchants (merchant_reference, name, category_code, country_code)
                            VALUES (?, 'Synthetic', '742', 'GB')
                            """, "MER-" + SchemaFixtures.next4()));
        }
    }

    @Nested
    @DisplayName("transactions (V3)")
    class Transactions {

        @Test
        @DisplayName("the same idempotency key twice on one account is refused")
        void idempotencyKeyIsUniquePerAccount() {
            UUID accountId = fixtures.insertAccount(fixtures.insertCustomer());
            UUID merchantId = fixtures.insertMerchant();
            String key = "retry-" + SchemaFixtures.next6();
            fixtures.insertTransaction(accountId, merchantId, key);

            // This is the whole idempotency guarantee. Ingestion is
            // at-least-once, so this write is what a retry looks like.
            assertViolates(
                    "transactions_idempotency_unique", () -> fixtures.insertTransaction(accountId, merchantId, key));
        }

        @Test
        @DisplayName("the same idempotency key on a different account is not a duplicate")
        void idempotencyKeyIsNotGlobal() {
            UUID merchantId = fixtures.insertMerchant();
            UUID firstAccount = fixtures.insertAccount(fixtures.insertCustomer());
            UUID secondAccount = fixtures.insertAccount(fixtures.insertCustomer());
            String key = "shared-" + SchemaFixtures.next6();
            fixtures.insertTransaction(firstAccount, merchantId, key);

            // Two clients choosing the same key for two different accounts are
            // not retries of each other, and refusing the second would drop a
            // real transaction.
            assertThatCode(() -> fixtures.insertTransaction(secondAccount, merchantId, key))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a zero-value movement is refused")
        void amountMustBeNonZero() {
            UUID accountId = fixtures.insertAccount(fixtures.insertCustomer());
            UUID merchantId = fixtures.insertMerchant();

            assertViolates(
                    "transactions_amount_nonzero",
                    () -> jdbc.update(
                            """
                            INSERT INTO transactions (
                                transaction_reference, idempotency_key, account_id, merchant_id,
                                type, channel, amount, currency, origin_country,
                                occurred_at, ingestion_source, processing_status, correlation_id)
                            VALUES (?, ?, ?, ?, 'PURCHASE', 'CARD_NOT_PRESENT', 0, 'GBP', 'GB',
                                    now(), 'API', 'PENDING', gen_random_uuid())
                            """,
                            "TXN-" + SchemaFixtures.next6(),
                            "zero-" + SchemaFixtures.next6(),
                            accountId,
                            merchantId));
        }

        @Test
        @DisplayName("a malformed device handle is refused, but no device at all is a real answer")
        void deviceReferenceIsFormattedOrAbsent() {
            UUID accountId = fixtures.insertAccount(fixtures.insertCustomer());
            UUID merchantId = fixtures.insertMerchant();

            assertViolates(
                    "transactions_device_format",
                    () -> jdbc.update(
                            """
                            INSERT INTO transactions (
                                transaction_reference, idempotency_key, account_id, merchant_id,
                                type, channel, amount, currency, origin_country, device_reference,
                                occurred_at, ingestion_source, processing_status, correlation_id)
                            VALUES (?, ?, ?, ?, 'PURCHASE', 'CARD_PRESENT', 10, 'GBP', 'GB', 'DEV-XYZ',
                                    now(), 'API', 'PENDING', gen_random_uuid())
                            """,
                            "TXN-" + SchemaFixtures.next6(),
                            "dev-" + SchemaFixtures.next6(),
                            accountId,
                            merchantId));

            // A direct debit has no device. Null means that, and is allowed.
            assertThatCode(() -> fixtures.insertTransaction(accountId, merchantId, "nodev-" + SchemaFixtures.next6()))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("assessments and alerts (V4)")
    class AssessmentsAndAlerts {

        @Test
        @DisplayName("a degraded assessment cannot carry a model score")
        void degradedAssessmentHasNoModelOutput() {
            UUID transactionId = fixtures.insertTransaction();

            // This is the shape a partially-failed scoring path produces: the
            // call timed out, the flag was set, and a half-populated result was
            // written anyway. It would then be read as a real model output
            // forever.
            assertViolates("risk_assessments_degraded_consistent", () -> jdbc.update("""
                            INSERT INTO risk_assessments (
                                transaction_id, assessment_version, rule_score, model_score, final_score,
                                risk_band, degraded, model_version, feature_version, policy_version,
                                reason_codes, scoring_latency_ms, alert_raised, assessed_at)
                            VALUES (?, 1, 40.00, 60.00, 55.00, 'HIGH', true, NULL, NULL, '1.0.0',
                                    '[{"code":"RULE_ONLY","description":"d","contribution":0,"source":"RULE"}]'::jsonb, 0, false, now())
                            """, transactionId));
        }

        @Test
        @DisplayName("a non-degraded assessment cannot omit the model that produced it")
        void scoredAssessmentNamesItsModel() {
            UUID transactionId = fixtures.insertTransaction();

            assertViolates("risk_assessments_degraded_consistent", () -> jdbc.update("""
                            INSERT INTO risk_assessments (
                                transaction_id, assessment_version, rule_score, model_score, final_score,
                                risk_band, degraded, model_version, feature_version, policy_version,
                                reason_codes, scoring_latency_ms, alert_raised, assessed_at)
                            VALUES (?, 1, 40.00, 60.00, 55.00, 'HIGH', false, NULL, NULL, '1.0.0',
                                    '[{"code":"VELOCITY_5M_HIGH","description":"d","contribution":25,"source":"RULE"}]'::jsonb, 12, false, now())
                            """, transactionId));
        }

        @Test
        @DisplayName("an assessment with no reason cannot be defended to anyone")
        void reasonCodesMustBeANonEmptyArray() {
            UUID transactionId = fixtures.insertTransaction();

            assertViolates("risk_assessments_reason_codes_shape", () -> jdbc.update("""
                            INSERT INTO risk_assessments (
                                transaction_id, assessment_version, rule_score, model_score, final_score,
                                risk_band, degraded, model_version, feature_version, policy_version,
                                reason_codes, scoring_latency_ms, alert_raised, assessed_at)
                            VALUES (?, 1, 40.00, 60.00, 55.00, 'HIGH', false, '1.0.0', '1.0.0', '1.0.0',
                                    '[]'::jsonb, 12, false, now())
                            """, transactionId));
        }

        @Test
        @DisplayName("rescoring adds a version rather than colliding")
        void assessmentVersionIsUniquePerTransaction() {
            UUID transactionId = fixtures.insertTransaction();
            fixtures.insertAssessment(transactionId);

            assertViolates("risk_assessments_version_unique", () -> fixtures.insertAssessment(transactionId));
        }

        @Test
        @DisplayName("a live alert cannot have a close time")
        void openAlertHasNoCloseTime() {
            UUID transactionId = fixtures.insertTransaction();
            UUID assessmentId = fixtures.insertAssessment(transactionId);

            assertViolates(
                    "alerts_closed_at_consistent",
                    () -> jdbc.update("""
                            INSERT INTO alerts (
                                alert_reference, transaction_id, assessment_id, status, priority,
                                summary, risk_band, final_score, closed_at)
                            VALUES (?, ?, ?, 'NEW', 'HIGH', 'Still open', 'HIGH', 55.00, now())
                            """, "ALT-" + SchemaFixtures.next4(), transactionId, assessmentId));
        }

        @Test
        @DisplayName("a closed alert must say when, or resolution time is unanswerable")
        void closedAlertHasACloseTime() {
            UUID transactionId = fixtures.insertTransaction();
            UUID assessmentId = fixtures.insertAssessment(transactionId);

            assertViolates(
                    "alerts_closed_at_consistent",
                    () -> jdbc.update("""
                            INSERT INTO alerts (
                                alert_reference, transaction_id, assessment_id, status, priority,
                                summary, risk_band, final_score)
                            VALUES (?, ?, ?, 'CONFIRMED_SUSPICIOUS', 'HIGH', 'Closed', 'HIGH', 55.00)
                            """, "ALT-" + SchemaFixtures.next4(), transactionId, assessmentId));
        }

        @Test
        @DisplayName("retrying alert creation cannot open a second alert for one decision")
        void oneAlertPerAssessment() {
            UUID transactionId = fixtures.insertTransaction();
            UUID assessmentId = fixtures.insertAssessment(transactionId);
            fixtures.insertAlert(transactionId, assessmentId);

            assertViolates("alerts_assessment_unique", () -> fixtures.insertAlert(transactionId, assessmentId));
        }

        @Test
        @DisplayName("a transition that does not say what it moved from is not a record of one")
        void transitionActionRecordsBothEnds() {
            UUID transactionId = fixtures.insertTransaction();
            UUID assessmentId = fixtures.insertAssessment(transactionId);
            UUID alertId = fixtures.insertAlert(transactionId, assessmentId);
            UUID actorId = fixtures.systemUserId();

            assertViolates("alert_actions_transition_complete", () -> jdbc.update("""
                            INSERT INTO alert_actions (
                                alert_id, actor_id, actor_role, action_type, new_status, correlation_id)
                            VALUES (?, ?, 'SYSTEM', 'TRANSITIONED', 'IN_REVIEW', gen_random_uuid())
                            """, alertId, actorId));
        }

        @Test
        @DisplayName("one analyst gives one label per assessment")
        void feedbackIsUniquePerAnalystAndAssessment() {
            UUID transactionId = fixtures.insertTransaction();
            UUID assessmentId = fixtures.insertAssessment(transactionId);
            UUID actorId = fixtures.systemUserId();
            jdbc.update(
                    "INSERT INTO analyst_feedback (assessment_id, actor_id, label) VALUES (?, ?, 'TRUE_POSITIVE')",
                    assessmentId,
                    actorId);

            // A second, contradictory label from the same person would poison a
            // training set with no principled way to choose between them.
            assertViolates(
                    "analyst_feedback_unique",
                    () -> jdbc.update(
                            "INSERT INTO analyst_feedback (assessment_id, actor_id, label) "
                                    + "VALUES (?, ?, 'FALSE_POSITIVE')",
                            assessmentId,
                            actorId));
        }
    }

    @Nested
    @DisplayName("model registry (V5)")
    class ModelRegistry {

        private static final String FINGERPRINT = "a".repeat(64);
        private static final String CHECKSUM = "b".repeat(64);

        private void insertModel(String modelVersion, String status, boolean promoted) {
            jdbc.update("""
                    INSERT INTO model_registry (
                        model_version, feature_version, training_data_fingerprint, artifact_checksum,
                        metrics, status, trained_at, promoted_at)
                    VALUES (?, '1.0.0', ?, ?, '{"prAuc": 0.5}'::jsonb, ?, now(), CASE WHEN ? THEN now() END)
                    """, modelVersion, FINGERPRINT, CHECKSUM, status, promoted);
        }

        @Test
        @DisplayName("a second active model is refused, so no assessment is ambiguous about its model")
        void onlyOneModelCanBeActive() {
            insertModel("9.0." + SchemaFixtures.next4(), "ACTIVE", true);

            assertViolates(
                    "model_registry_single_active_idx",
                    () -> insertModel("9.1." + SchemaFixtures.next4(), "ACTIVE", true));
        }

        @Test
        @DisplayName("an active model that was never promoted lost its own history")
        void activeModelMustHaveBeenPromoted() {
            assertViolates(
                    "model_registry_promotion_consistent",
                    () -> insertModel("9.2." + SchemaFixtures.next4(), "ACTIVE", false));
        }

        @Test
        @DisplayName("candidates and retired models are not covered by the single-active index")
        void severalCandidatesCoexist() {
            assertThatCode(() -> {
                        insertModel("8.0." + SchemaFixtures.next4(), "CANDIDATE", false);
                        insertModel("8.1." + SchemaFixtures.next4(), "CANDIDATE", false);
                    })
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("outbox, deduplication and audit (V6)")
    class MessagingAndAudit {

        private void insertOutbox(String status, boolean published) {
            jdbc.update("""
                    INSERT INTO outbox_events (
                        aggregate_type, aggregate_id, event_type, schema_version, partition_key,
                        payload, status, correlation_id, occurred_at, published_at)
                    VALUES ('transaction', gen_random_uuid(), 'transaction.created', 1, 'ACC-000001',
                            '{}'::jsonb, ?, gen_random_uuid(), now(), CASE WHEN ? THEN now() END)
                    """, status, published);
        }

        @Test
        @DisplayName("a published event must say when it was published")
        void publishedEventRecordsItsPublicationTime() {
            // Without this the outbox cannot answer how far behind it was,
            // which is the one operational question it exists to answer.
            assertViolates("outbox_events_published_at_consistent", () -> insertOutbox("PUBLISHED", false));
        }

        @Test
        @DisplayName("a pending event cannot claim a publication time")
        void pendingEventHasNoPublicationTime() {
            assertViolates("outbox_events_published_at_consistent", () -> insertOutbox("PENDING", true));
        }

        @Test
        @DisplayName("an event type outside the contract is refused")
        void eventTypeMustBeOneTheContractDefines() {
            assertViolates("outbox_events_type_known", () -> jdbc.update("""
                            INSERT INTO outbox_events (
                                aggregate_type, aggregate_id, event_type, schema_version, partition_key,
                                payload, status, correlation_id, occurred_at)
                            VALUES ('transaction', gen_random_uuid(), 'transaction.invented', 1, 'ACC-000001',
                                    '{}'::jsonb, 'PENDING', gen_random_uuid(), now())
                            """));
        }

        @Test
        @DisplayName("a second delivery to the same consumer is a no-op, not a second effect")
        void processedEventsDeduplicatePerConsumer() {
            UUID eventId = jdbc.queryForObject("SELECT gen_random_uuid()", UUID.class);
            jdbc.update(
                    "INSERT INTO processed_events (consumer_name, event_id) VALUES ('alert-projector', ?)", eventId);

            assertViolates(
                    "processed_events_pk",
                    () -> jdbc.update(
                            "INSERT INTO processed_events (consumer_name, event_id) VALUES ('alert-projector', ?)",
                            eventId));

            // A different consumer legitimately processes the same event.
            assertThatCode(() -> jdbc.update(
                            "INSERT INTO processed_events (consumer_name, event_id) VALUES ('audit-writer', ?)",
                            eventId))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a user action with no user is not attributable")
        void userAuditEntryNamesItsUser() {
            assertViolates("audit_log_user_attributed", () -> jdbc.update("""
                            INSERT INTO audit_log (actor_type, action, resource_type, correlation_id)
                            VALUES ('USER', 'ALERT_TRANSITIONED', 'alert', gen_random_uuid())
                            """));
        }

        @Test
        @DisplayName("a system action needs no user")
        void systemAuditEntryNeedsNoUser() {
            assertThatCode(() -> jdbc.update("""
                            INSERT INTO audit_log (actor_type, action, resource_type, correlation_id)
                            VALUES ('SYSTEM', 'OUTBOX_PUBLISHED', 'transaction', gen_random_uuid())
                            """)).doesNotThrowAnyException();
        }
    }
}
