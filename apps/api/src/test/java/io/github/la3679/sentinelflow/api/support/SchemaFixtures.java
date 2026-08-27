/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.support;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Valid rows to hang constraint tests off, inserted with plain SQL.
 *
 * <p><strong>SQL rather than JPA, deliberately.</strong> These suites test the schema, so going
 * through Hibernate would put a second implementation between the assertion and the thing being
 * asserted - and a mapping bug would then look exactly like a constraint bug. Nothing here uses an
 * entity class.
 *
 * <p>Every reference is unique per call, because one container serves the whole fork and the rows
 * one test writes are still there for the next. The counter starts high enough that a value never
 * collides with the reference data V1 inserts.
 */
public final class SchemaFixtures {

    private static final AtomicInteger SEQUENCE = new AtomicInteger(1);

    private final JdbcTemplate jdbc;

    public SchemaFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A six-digit suffix for CUS-, ACC- and TXN- references. */
    public static String next6() {
        return "%06d".formatted(SEQUENCE.getAndIncrement());
    }

    /** A four-digit suffix for MER- and ALT- references. */
    public static String next4() {
        return "%04d".formatted(SEQUENCE.getAndIncrement());
    }

    public UUID systemUserId() {
        return jdbc.queryForObject("SELECT id FROM users WHERE username = 'system'", UUID.class);
    }

    public UUID insertCustomer() {
        return jdbc.queryForObject("""
                INSERT INTO customers (customer_reference, country_code, risk_tier, status)
                VALUES (?, 'GB', 'STANDARD', 'ACTIVE')
                RETURNING id
                """, UUID.class, "CUS-" + next6());
    }

    public UUID insertAccount(UUID customerId) {
        return jdbc.queryForObject("""
                INSERT INTO accounts (customer_id, account_reference, currency, balance, status, opened_at)
                VALUES (?, ?, 'GBP', 1000.0000, 'ACTIVE', now())
                RETURNING id
                """, UUID.class, customerId, "ACC-" + next6());
    }

    public UUID insertMerchant() {
        return jdbc.queryForObject("""
                INSERT INTO merchants (merchant_reference, name, category_code, country_code)
                VALUES (?, 'Synthetic Supplies', '5411', 'GB')
                RETURNING id
                """, UUID.class, "MER-" + next4());
    }

    /** A pending transaction on a fresh account and merchant. */
    public UUID insertTransaction() {
        return insertTransaction(insertAccount(insertCustomer()), insertMerchant(), "idem-" + next6());
    }

    public UUID insertTransaction(UUID accountId, UUID merchantId, String idempotencyKey) {
        return jdbc.queryForObject("""
                INSERT INTO transactions (
                    transaction_reference, idempotency_key, account_id, merchant_id,
                    type, channel, amount, currency, origin_country,
                    occurred_at, ingestion_source, processing_status, correlation_id)
                VALUES (?, ?, ?, ?, 'PURCHASE', 'CARD_NOT_PRESENT', 42.5000, 'GBP', 'GB',
                        now(), 'API', 'PENDING', gen_random_uuid())
                RETURNING id
                """, UUID.class, "TXN-" + next6(), idempotencyKey, accountId, merchantId);
    }

    /**
     * A pending transaction that originates somewhere specific.
     *
     * <p>Exists so a suite can build an account history the rule engine actually reacts to. Every
     * other fixture here originates in GB, which is correct for a schema test and useless for one
     * that needs {@code COUNTRY_CHANGE} to fire.
     */
    public UUID insertTransactionFrom(UUID accountId, UUID merchantId, String idempotencyKey, String originCountry) {
        return jdbc.queryForObject(
                """
                INSERT INTO transactions (
                    transaction_reference, idempotency_key, account_id, merchant_id,
                    type, channel, amount, currency, origin_country,
                    occurred_at, ingestion_source, processing_status, correlation_id)
                VALUES (?, ?, ?, ?, 'PURCHASE', 'CARD_NOT_PRESENT', 42.5000, 'GBP', ?,
                        now(), 'API', 'PENDING', gen_random_uuid())
                RETURNING id
                """, UUID.class, "TXN-" + next6(), idempotencyKey, accountId, merchantId, originCountry);
    }

    /** A non-degraded assessment: every model-derived field present, as the CHECK requires. */
    public UUID insertAssessment(UUID transactionId) {
        return jdbc.queryForObject("""
                INSERT INTO risk_assessments (
                    transaction_id, assessment_version, rule_score, model_score, final_score,
                    risk_band, degraded, model_version, feature_version, policy_version,
                    reason_codes, scoring_latency_ms, alert_raised, assessed_at, ruleset_version)
                VALUES (?, 1, 40.00, 60.00, 55.00, 'HIGH', false, '1.0.0', '1.0.0', '1.0.0',
                        '[{"code":"VELOCITY_5M_HIGH","description":"Synthetic reason for a schema test","contribution":25,"source":"RULE"}]'::jsonb, 12, true, now(), '1.0.0')
                RETURNING id
                """, UUID.class, transactionId);
    }

    /** A live alert: status NEW, so closed_at stays null as the CHECK requires. */
    public UUID insertAlert(UUID transactionId, UUID assessmentId) {
        return jdbc.queryForObject("""
                INSERT INTO alerts (
                    alert_reference, transaction_id, assessment_id, status, priority,
                    summary, risk_band, final_score)
                VALUES (?, ?, ?, 'NEW', 'HIGH', 'Synthetic alert for a schema test', 'HIGH', 55.00)
                RETURNING id
                """, UUID.class, "ALT-" + next4(), transactionId, assessmentId);
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }
}
