/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.support;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * one test writes are still there for the next.
 *
 * <h2>Two of these references have an allocator already, and it is not this class</h2>
 *
 * {@code transaction_reference} and {@code alert_reference} are handed out by
 * {@code transaction_reference_seq} and {@code alert_reference_seq}, which the application reads on
 * every ingestion and every raised alert. A counter here that also starts at 1 is a <em>second</em>
 * allocator into a namespace with a unique constraint over it, and the two meet as soon as the
 * application has ingested as many transactions as the fixtures have written. That is exactly what
 * happened: {@code TransactionIngestionIT} failed with a duplicate on {@code TXN-000005} on a runner
 * where the suite order put five fixture rows in front of it, and passed on a machine where it did
 * not.
 *
 * <p><strong>So the fixtures allocate those two from the same sequences.</strong> One allocator per
 * namespace makes a collision impossible rather than unlikely, which a shared counter and a large
 * enough starting offset could only ever be. {@link #next6()} and {@link #next4()} remain for the
 * references nothing else allocates — {@code CUS-}, {@code ACC-}, {@code MER-}, idempotency keys —
 * and must not be used for a transaction or an alert.
 */
public final class SchemaFixtures {

    private static final AtomicInteger SEQUENCE = new AtomicInteger(1);

    private final JdbcTemplate jdbc;

    public SchemaFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A six-digit suffix for CUS- and ACC- references.
     *
     * <p><strong>Not for TXN-.</strong> See {@link #nextTransactionReference(JdbcTemplate)}.
     */
    public static String next6() {
        return "%06d".formatted(SEQUENCE.getAndIncrement());
    }

    /**
     * A four-digit suffix for MER- references.
     *
     * <p><strong>Not for ALT-.</strong> See {@link #nextAlertReference(JdbcTemplate)}.
     */
    public static String next4() {
        return "%04d".formatted(SEQUENCE.getAndIncrement());
    }

    /**
     * The next transaction reference, from the sequence the application itself reads.
     *
     * <p>Static and taking the template, so a suite that holds a {@code JdbcTemplate} but no
     * {@code SchemaFixtures} can still allocate correctly — the wrong call is the easy one to make
     * here, and the right one should not require restructuring a test class to reach.
     */
    public static String nextTransactionReference(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
                "SELECT 'TXN-' || lpad(nextval('transaction_reference_seq')::text, 6, '0')", String.class);
    }

    /** The next alert reference, from the sequence {@code AlertRaiser} reads. */
    public static String nextAlertReference(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT 'ALT-' || lpad(nextval('alert_reference_seq')::text, 4, '0')", String.class);
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
        return jdbc.queryForObject(
                """
                INSERT INTO transactions (
                    transaction_reference, idempotency_key, account_id, merchant_id,
                    type, channel, amount, currency, origin_country,
                    occurred_at, ingestion_source, processing_status, correlation_id)
                VALUES (?, ?, ?, ?, 'PURCHASE', 'CARD_NOT_PRESENT', 42.5000, 'GBP', 'GB',
                        now(), 'API', 'PENDING', gen_random_uuid())
                RETURNING id
                """, UUID.class, nextTransactionReference(jdbc), idempotencyKey, accountId, merchantId);
    }

    /**
     * A pending transaction that originates somewhere specific, at an instant the caller chooses.
     *
     * <p>Exists so a suite can build an account history the rule engine actually reacts to. Every
     * other fixture here originates in GB and occurs at {@code now()}, which is correct for a schema
     * test and useless for one that needs {@code COUNTRY_CHANGE} and {@code VELOCITY_5M_HIGH} to
     * fire.
     *
     * <p><strong>The instant is a parameter because the ruleset reads the clock through it.</strong>
     * {@code OFF_HOURS} fires between 02:00 and 04:59 UTC and the velocity window is five minutes
     * wide, so a history built from {@code now()} would score differently on a build that ran
     * overnight than on one that ran at noon — a rule score that depends on when the suite happened
     * to start is one no assertion can state.
     */
    public UUID insertTransactionFrom(
            UUID accountId, UUID merchantId, String idempotencyKey, String originCountry, Instant occurredAt) {
        return jdbc.queryForObject(
                """
                INSERT INTO transactions (
                    transaction_reference, idempotency_key, account_id, merchant_id,
                    type, channel, amount, currency, origin_country,
                    occurred_at, ingestion_source, processing_status, correlation_id)
                VALUES (?, ?, ?, ?, 'PURCHASE', 'CARD_NOT_PRESENT', 42.5000, 'GBP', ?,
                        ?, 'API', 'PENDING', gen_random_uuid())
                RETURNING id
                """,
                UUID.class,
                nextTransactionReference(jdbc),
                idempotencyKey,
                accountId,
                merchantId,
                originCountry,
                // OffsetDateTime rather than java.sql.Timestamp: the column is
                // timestamptz, and the driver maps this one without routing the
                // value through the JVM's default zone on the way.
                OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC));
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
                """, UUID.class, nextAlertReference(jdbc), transactionId, assessmentId);
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }
}
