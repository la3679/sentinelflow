/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

import io.github.la3679.sentinelflow.api.domain.RoleCode;
import io.github.la3679.sentinelflow.api.security.TokenIssuer;
import io.github.la3679.sentinelflow.api.support.AbstractPostgresTest;
import io.github.la3679.sentinelflow.api.support.SchemaFixtures;
import io.github.la3679.sentinelflow.api.support.TestCredentials;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reading transactions, and the decisions behind them.
 *
 * <p><strong>Every transaction this suite reads is one it created.</strong> One container serves the
 * whole fork and other suites leave rows behind, so nothing here asserts a total or a first element:
 * the assertions are about this suite's own rows, found by their references.
 *
 * <p>Each test draws its own account so the {@code accountReference} filter has something to be
 * exact about, and its own occurrence window for the same reason — a shared window would make the
 * filter tests count rows the tests before them left.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransactionReadIT extends AbstractPostgresTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final String SUFFIX = Long.toString(System.nanoTime() % 100_000L);

    /** A base instant far enough back that no other suite's {@code now()} rows land in a window here. */
    private static final Instant EPOCH = Instant.parse("2024-03-01T12:00:00Z");

    private static int windowNumber = 0;

    @LocalServerPort
    private int port;

    @Autowired
    private TokenIssuer tokens;

    @Autowired
    private JdbcTemplate jdbc;

    private SchemaFixtures fixtures;
    private RestTestClient client;
    private String analystToken;
    private String auditorToken;
    private UUID accountId;
    private String accountReference;
    private UUID merchantId;
    private Instant windowStart;

    @BeforeEach
    void setUp() {
        fixtures = new SchemaFixtures(jdbc);
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        analystToken = tokens.issue(
                        operator("txn.analyst" + SUFFIX, RoleCode.ANALYST), List.of(RoleCode.ANALYST), Instant.now())
                .value();
        auditorToken = tokens.issue(
                        operator("txn.auditor" + SUFFIX, RoleCode.AUDITOR), List.of(RoleCode.AUDITOR), Instant.now())
                .value();

        accountId = fixtures.insertAccount(fixtures.insertCustomer());
        accountReference =
                jdbc.queryForObject("SELECT account_reference FROM accounts WHERE id = ?", String.class, accountId);
        merchantId = fixtures.insertMerchant();

        // Two hours apart rather than adjacent, so a test that deliberately
        // reads the window immediately after its own cannot land in the next
        // test's - the mistake AlertReportIT's fixture made and recorded.
        windowStart = EPOCH.plusSeconds(7_200L * windowNumber++);
    }

    // ----------------------------------------------------------------------- //
    // The list
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("a transaction is published by its references, never by the keys behind them")
    void publishesReferences() {
        UUID transactionId = transactionAt(windowStart);

        JsonNode row = only(list("?accountReference=" + accountReference + "&size=200"));

        // ADR-0007: the reference is what a person reads and the identifier is
        // what a client routes on. A response carrying only the account's UUID
        // would be one no operator could act on.
        assertThat(row.get("transactionId").asString()).isEqualTo(transactionId.toString());
        assertThat(row.get("accountReference").asString()).isEqualTo(accountReference);
        assertThat(row.get("merchantReference").asString()).startsWith("MER-");
        assertThat(row.get("transactionReference").asString()).startsWith("TXN-");
        assertThat(row.get("merchantCategoryCode").asString()).isEqualTo("5411");
    }

    @Test
    @DisplayName("an amount is a decimal string with its currency, never a JSON number")
    void publishesMoneyAsAString() {
        transactionAt(windowStart);

        JsonNode amount = only(list("?accountReference=" + accountReference)).get("amount");

        // A JSON number is a double by the time JavaScript sees it, and the
        // value is wrong before anyone can defend it (ADR-0007).
        assertThat(amount.get("value").isString()).isTrue();
        assertThat(amount.get("value").asString()).isEqualTo("42.5000");
        assertThat(amount.get("currency").asString()).isEqualTo("GBP");
    }

    @Test
    @DisplayName("an unassessed transaction has a null band and says so in its processing status")
    void carriesNoBandBeforeItIsScored() {
        transactionAt(windowStart);

        JsonNode row = only(list("?accountReference=" + accountReference));

        // Ingestion answers 202 and scoring happens afterwards, so this is a
        // normal state rather than a missing row. An inner join on the
        // assessment would have hidden it.
        assertThat(row.get("riskBand").isNull()).isTrue();
        assertThat(row.get("processingStatus").asString()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("the band is the current assessment's, and a rescoring does not duplicate the row")
    void takesTheBandFromTheCurrentAssessment() {
        UUID transactionId = transactionAt(windowStart);
        fixtures.insertAssessment(transactionId);
        rescore(transactionId, 2, "CRITICAL");

        JsonNode row = only(list("?accountReference=" + accountReference));

        // A plain join on transactionId would page one row per assessment, so a
        // rescored transaction would appear twice and the pair would disagree.
        assertThat(row.get("riskBand").asString()).isEqualTo("CRITICAL");
    }

    @Test
    @DisplayName("the band filter reads the current assessment too")
    void filtersOnTheCurrentBand() {
        UUID transactionId = transactionAt(windowStart);
        fixtures.insertAssessment(transactionId);
        rescore(transactionId, 2, "CRITICAL");

        assertThat(references(list("?accountReference=" + accountReference + "&riskBand=CRITICAL")))
                .hasSize(1);
        assertThat(references(list("?accountReference=" + accountReference + "&riskBand=HIGH")))
                .as("HIGH is the version this transaction was rescored out of")
                .isEmpty();
    }

    @Test
    @DisplayName("the newest transaction comes first")
    void ordersNewestFirst() {
        transactionAt(windowStart);
        transactionAt(windowStart.plusSeconds(60));
        transactionAt(windowStart.plusSeconds(120));

        List<String> occurred = new ArrayList<>();
        list("?accountReference=" + accountReference + "&size=200")
                .get("content")
                .forEach(node -> occurred.add(node.get("occurredAt").asString()));

        assertThat(occurred).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    @DisplayName("the occurrence window is half-open, so a boundary row is counted once")
    void theWindowIsHalfOpen() {
        transactionAt(windowStart);

        Instant next = windowStart.plusSeconds(3_600);
        assertThat(references(list(window(windowStart, next)))).hasSize(1);
        assertThat(references(list(window(next, next.plusSeconds(3_600)))))
                .as("a row on the boundary belongs to the window that starts on it, and to no other")
                .isEmpty();
    }

    @Test
    @DisplayName("a window that ends before it starts is refused rather than answered with nothing")
    void refusesAnInvertedWindow() {
        get("/api/v1/transactions" + window(windowStart.plusSeconds(3_600), windowStart), analystToken, 422);
    }

    @Test
    @DisplayName("an oversize page is refused rather than clamped, here as everywhere")
    void refusesAnOversizePage() {
        get("/api/v1/transactions?size=500", analystToken, 422);
    }

    @Test
    @DisplayName("an auditor can read transactions")
    void auditorsCanRead() {
        transactionAt(windowStart);

        // Read-only is a statement about mutations. An auditor who could not see
        // the traffic could not audit any decision made about it.
        get("/api/v1/transactions?accountReference=" + accountReference, auditorToken, 200);
    }

    @Test
    @DisplayName("reading transactions needs an operator token, and posting one needs a different credential")
    void refusesAnAnonymousRead() {
        // Two credentials on one path, and that is the decision rather than an
        // accident. Reading other people's activity is an operator's
        // permission; posting is a pipeline's, and since ADR-0017 §1 it carries
        // its own key. An operator token is not accepted for the POST and the
        // ingestion key is not accepted for the GET.
        client.get().uri("/api/v1/transactions").exchange().expectStatus().isUnauthorized();

        client.get()
                .uri("/api/v1/transactions")
                .header(ApiHeaders.API_KEY, TestCredentials.INGEST_API_KEY)
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    // ----------------------------------------------------------------------- //
    // One transaction, and its assessment
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("one transaction is readable by its identifier, and an unknown one is a not-found")
    void readsOneTransaction() {
        UUID transactionId = transactionAt(windowStart);

        JsonNode body = get("/api/v1/transactions/" + transactionId, analystToken, 200);

        assertThat(body.get("transactionId").asString()).isEqualTo(transactionId.toString());
        assertThat(body.get("accountReference").asString()).isEqualTo(accountReference);

        get("/api/v1/transactions/" + UUID.randomUUID(), analystToken, 404);
    }

    @Test
    @DisplayName("the assessment carries every version that contributed to the score")
    void readsTheAssessment() {
        UUID transactionId = transactionAt(windowStart);
        UUID assessmentId = fixtures.insertAssessment(transactionId);

        JsonNode body = get("/api/v1/transactions/" + transactionId + "/assessment", analystToken, 200);

        assertThat(body.get("assessmentId").asString()).isEqualTo(assessmentId.toString());
        assertThat(body.get("degraded").asBoolean()).isFalse();
        // Four versions rather than one: they move independently, and a score
        // an analyst defends later is only defensible if what produced it can
        // be named.
        assertThat(body.get("modelVersion").asString()).isEqualTo("1.0.0");
        assertThat(body.get("featureVersion").asString()).isEqualTo("1.0.0");
        assertThat(body.get("rulesetVersion").asString()).isEqualTo("1.0.0");
        assertThat(body.get("policyVersion").asString()).isEqualTo("1.0.0");
        assertThat(body.get("reasonCodes")).hasSize(1);
        assertThat(body.get("reasonCodes").get(0).get("code").asString()).isEqualTo("VELOCITY_5M_HIGH");
    }

    @Test
    @DisplayName("the assessment read answers with the current version, not the first")
    void readsTheCurrentAssessment() {
        UUID transactionId = transactionAt(windowStart);
        fixtures.insertAssessment(transactionId);
        rescore(transactionId, 2, "CRITICAL");

        JsonNode body = get("/api/v1/transactions/" + transactionId + "/assessment", analystToken, 200);

        // A rescoring writes a new row rather than editing the decision that was
        // acted on, so "the" assessment has to mean the highest version.
        assertThat(body.get("riskBand").asString()).isEqualTo("CRITICAL");
    }

    @Test
    @DisplayName("a transaction that exists and has not been scored answers not-found, and says why")
    void answersNotFoundWhileScoringIsInFlight() {
        UUID transactionId = transactionAt(windowStart);

        JsonNode problem = get("/api/v1/transactions/" + transactionId + "/assessment", analystToken, 404);

        // 404 is the contract's answer, and a client polling for the assessment
        // reads it as "not yet". The detail is what distinguishes it from a
        // transaction that does not exist.
        assertThat(problem.get("type").asString()).endsWith("assessment-not-found");
    }

    @Test
    @DisplayName("an assessment for a transaction that does not exist is a different not-found")
    void distinguishesAnUnknownTransaction() {
        JsonNode problem = get("/api/v1/transactions/" + UUID.randomUUID() + "/assessment", analystToken, 404);

        assertThat(problem.get("type").asString()).endsWith("transaction-not-found");
    }

    // ----------------------------------------------------------------------- //
    // Fixtures and reads
    // ----------------------------------------------------------------------- //

    private UUID operator(String username, RoleCode role) {
        Integer existing =
                jdbc.queryForObject("SELECT count(*) FROM users WHERE username = ?", Integer.class, username);
        if (existing == null || existing == 0) {
            jdbc.update(
                    "INSERT INTO users (username, display_name, status) VALUES (?, ?, 'ACTIVE')",
                    username,
                    "Integration test " + role);
            jdbc.update("""
                    INSERT INTO user_roles (user_id, role_id)
                    SELECT u.id, r.id FROM users u, roles r WHERE u.username = ? AND r.code = ?
                    """, username, role.name());
        }
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", UUID.class, username);
    }

    private UUID transactionAt(Instant occurredAt) {
        return fixtures.insertTransactionFrom(accountId, merchantId, "idem-" + UUID.randomUUID(), "GB", occurredAt);
    }

    /** A second assessment for the same transaction, as a rescoring under a new policy writes one. */
    private void rescore(UUID transactionId, int version, String band) {
        jdbc.update("""
                INSERT INTO risk_assessments (
                    transaction_id, assessment_version, rule_score, model_score, final_score,
                    risk_band, degraded, model_version, feature_version, policy_version,
                    reason_codes, scoring_latency_ms, alert_raised, assessed_at, ruleset_version)
                VALUES (?, ?, 40.00, 95.00, 92.00, ?, false, '1.0.0', '1.0.0', '1.1.0',
                        '[{"code":"VELOCITY_5M_HIGH","description":"Synthetic reason for a read test","contribution":25,"source":"RULE"}]'::jsonb,
                        12, true, now(), '1.0.0')
                """, transactionId, version, band);
    }

    private static String window(Instant from, Instant to) {
        return "?occurredAfter=" + iso(from) + "&occurredBefore=" + iso(to);
    }

    private static String iso(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC).toString().replace("+", "%2B");
    }

    private JsonNode list(String query) {
        return get("/api/v1/transactions" + query, analystToken, 200);
    }

    private JsonNode get(String uri, String token, int expected) {
        return MAPPER.readTree(client.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isEqualTo(expected)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody());
    }

    private static List<String> references(JsonNode page) {
        List<String> found = new ArrayList<>();
        page.get("content")
                .forEach(node -> found.add(node.get("transactionReference").asString()));
        return found;
    }

    /** The one row this suite's account has, asserted to be exactly one. */
    private static JsonNode only(JsonNode page) {
        assertThat(page.get("content")).hasSize(1);
        return page.get("content").get(0);
    }
}
