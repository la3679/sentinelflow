/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import io.github.la3679.sentinelflow.api.domain.RoleCode;
import io.github.la3679.sentinelflow.api.domain.TransactionChannel;
import io.github.la3679.sentinelflow.api.domain.TransactionType;
import io.github.la3679.sentinelflow.api.support.AbstractPostgresTest;
import io.github.la3679.sentinelflow.api.support.SchemaFixtures;
import io.github.la3679.sentinelflow.api.web.dto.AmountRequest;
import io.github.la3679.sentinelflow.api.web.dto.TransactionRequest;

/**
 * What this service writes to its log, and what it must never write.
 *
 * <h2>Why this is an integration test and not a unit test</h2>
 *
 * ADR-0016 §4's rule is that a log line is built from named fields chosen at the call site, and the
 * only honest way to check it is to drive real requests through the real application and read what
 * came out. A unit test would format a line itself and search it, which proves that whoever wrote
 * the test picked a safe example. What is asserted here is every line that ingestion, validation,
 * the read paths, the alert workflow, reporting and the framework wrote, together.
 *
 * <h2>Every logger, at DEBUG, and nothing excused here</h2>
 *
 * ADR-0016 §4 says the redaction test captures "every line the service emits at every level
 * including {@code DEBUG}". The first version of this file ran the application's own package and
 * Spring's web layer at {@code DEBUG} and left the rest of the framework at its shipped level, which
 * is narrower than the claim: a leak from a library nobody thought about is exactly the leak a
 * hand-picked logger list misses. The root logger is at {@code DEBUG} here, so every logger in the
 * process is in scope.
 *
 * <p><strong>This file pins nothing.</strong> The one property above is the whole of its logging
 * configuration, so what protects the assertions is {@code application.yaml}'s own pins and nothing
 * that exists only under test. That is the point of doing it this way: a deployment gets the same
 * guarantee this test asserts, rather than a guarantee that holds because a test excused the loggers
 * it could not satisfy.
 *
 * <p>Three loggers are pinned there, each a debugging tool whose entire purpose is to print what
 * this rule forbids — {@code org.hibernate.orm.jdbc.error}, which predates this test,
 * {@code org.hibernate.orm.jdbc.bind}, and {@code org.hibernate.orm.core}, whose entity dumps carry
 * an amount, a device handle, an outbox payload and an analyst's note. The reasoning for each is
 * written beside it in {@code application.yaml}.
 *
 * <h2>Four paths, because the values are different on each</h2>
 *
 * Ingestion carries an amount, a device handle and a caller-chosen idempotency key inbound. The read
 * paths carry an amount <em>outbound</em>, which is a different failure — a response serialiser
 * logging what it is about to write. The alert workflow carries a bearer token on every request and
 * an operator's own words in a note. Signing in carries a password. All five values are on
 * ADR-0016 §4's forbidden list, and no earlier version of this file exercised the last three paths
 * at all.
 *
 * <h2>The planted values</h2>
 *
 * Distinctive enough that a match cannot be coincidence. A plausible amount like {@code 12.00} turns
 * up inside a timestamp or a duration eventually; {@code 4242.4242} does not.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "logging.level.root=DEBUG")
class LogRedactionIT extends AbstractPostgresTest {

    /** A monetary amount. Forbidden in a log at every level. */
    private static final String AMOUNT = "4242.4242";

    /** A device handle. Forbidden for the same reason. */
    private static final String DEVICE = "DEV-beefbeefbeef";

    /** Caller-controlled text, which is also the log-injection surface. */
    private static final String IDEMPOTENCY_KEY = "redaction-key-cafebabe-8f2a41d7";

    /** A credential, and the most clearly forbidden value in this file. */
    private static final String PASSWORD = "a-password-for-redaction-only-5b17c4";

    /** An analyst's own words on an alert. A request body fragment, and caller-controlled. */
    private static final String NOTE = "note-text-d41d8cd9-for-redaction";

    /** Unique per run: one container serves the whole fork and users outlive this suite. */
    private static final String SUFFIX = Long.toString(System.nanoTime() % 100_000L);

    private static final String ANALYST = "redaction.analyst" + SUFFIX;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * The JDK's client, deliberately, rather than {@code RestTestClient}.
     *
     * <p>Spring's {@code RestClient} logs the body it is about to write at {@code DEBUG}, and this
     * test turns everything up to {@code DEBUG}. Sending the request with one would put the amount,
     * the device handle and the idempotency key into the captured stream from <strong>the test's own
     * client</strong> — which is what the first version of this test did, and it failed for a reason
     * that had nothing to do with the application.
     *
     * <p>With the JDK's client, a {@code DefaultRestClient} line in this output can only have come
     * from the application's own outbound call, which is exactly what these assertions should be
     * allowed to catch.
     */
    private final HttpClient http = HttpClient.newBuilder().build();

    private SchemaFixtures fixtures;
    private UUID analystId;
    private String accountReference;
    private String merchantReference;
    private UUID accountId;
    private UUID merchantId;

    @BeforeEach
    void setUp() {
        fixtures = new SchemaFixtures(jdbc);
        UUID customerId = fixtures.insertCustomer();
        accountId = fixtures.insertAccount(customerId);
        merchantId = fixtures.insertMerchant();
        accountReference =
                jdbc.queryForObject("SELECT account_reference FROM accounts WHERE id = ?", String.class, accountId);
        merchantReference =
                jdbc.queryForObject("SELECT merchant_reference FROM merchants WHERE id = ?", String.class, merchantId);
        analystId = operatorWithPassword();
    }

    // ----------------------------------------------------------------------- //
    // Ingestion — values arriving
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("an accepted transaction leaves no amount, device or idempotency key in the log")
    void acceptedTransactionLeavesNothingForbidden(CapturedOutput output) {
        HttpResponse<String> response = post(body(IDEMPOTENCY_KEY, AMOUNT, "GB"));

        assertThat(response.statusCode()).isEqualTo(202);
        assertNothingForbiddenIn(output);
    }

    @Test
    @DisplayName("a refused transaction is described by field name, never by value")
    void refusedTransactionEchoesNoValues(CapturedOutput output) {
        HttpResponse<String> response = post(body(IDEMPOTENCY_KEY, AMOUNT, "NOT-A-COUNTRY"));

        // 422 rather than 400: the body parsed, and a field in it failed
        // validation. TransactionIngestionIT owns that distinction; what matters
        // here is only that the refusal happened, because a request the
        // application accepted would take a different path through the log.
        assertThat(response.statusCode()).isEqualTo(422);

        // The response is the other half of the same rule: a problem document
        // that echoed the body back would disclose to a caller what the log is
        // forbidden to disclose to an operator.
        assertThat(response.body()).contains("originCountry");
        assertThat(response.body()).doesNotContain(AMOUNT).doesNotContain(DEVICE);

        assertNothingForbiddenIn(output);
    }

    @Test
    @DisplayName("a duplicate key is normal traffic and still says nothing it should not")
    void replayedTransactionLeavesNothingForbidden(CapturedOutput output) {
        String submission = body(IDEMPOTENCY_KEY, AMOUNT, "GB");

        assertThat(post(submission).statusCode()).isEqualTo(202);
        assertThat(post(submission).statusCode())
                .as("the same key with the same payload is a replay, which is normal traffic")
                .isEqualTo(200);

        assertNothingForbiddenIn(output);
    }

    // ----------------------------------------------------------------------- //
    // The read paths — the same values going back out
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("reading a transaction back does not log the amount it is about to return")
    void readingATransactionLeavesNothingForbidden(CapturedOutput output) {
        UUID transactionId = plantedTransaction();
        String token = signIn();

        assertThat(get("/api/v1/transactions/" + transactionId, token).statusCode())
                .isEqualTo(200);
        assertThat(get("/api/v1/transactions?page=0&size=20", token).statusCode())
                .isEqualTo(200);

        // The response carries the amount, legitimately - it is the answer to
        // the question that was asked. The log must not, and a serialiser
        // logging what it is about to write is the way that happens.
        assertThat(get("/api/v1/transactions/" + transactionId, token).body())
                .as("the amount reaches the caller, so this asserts a redacted log rather than an "
                        + "endpoint that answers nothing")
                .contains(AMOUNT);

        assertNothingForbiddenIn(output, token);
    }

    // ----------------------------------------------------------------------- //
    // The alert workflow — a token on every request, and an analyst's own words
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("working an alert logs neither the bearer token nor the analyst's note")
    void theAlertWorkflowLeavesNothingForbidden(CapturedOutput output) {
        UUID transactionId = plantedTransaction();
        UUID alertId = fixtures.insertAlert(transactionId, fixtures.insertAssessment(transactionId));
        String token = signIn();

        assertThat(get("/api/v1/alerts?page=0&size=20", token).statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/alerts/" + alertId, token).statusCode()).isEqualTo(200);

        assertThat(put(
                                "/api/v1/alerts/" + alertId + "/assignment",
                                token,
                                json(Map.of("assigneeId", analystId.toString(), "expectedVersion", 0, "note", NOTE)))
                        .statusCode())
                .isEqualTo(200);
        assertThat(post("/api/v1/alerts/" + alertId + "/notes", token, json(Map.of("note", NOTE)))
                        .statusCode())
                .isEqualTo(201);
        assertThat(post(
                                "/api/v1/alerts/" + alertId + "/transition",
                                token,
                                json(Map.of("targetStatus", "IN_REVIEW", "expectedVersion", 1, "note", NOTE)))
                        .statusCode())
                .isEqualTo(200);
        assertThat(get("/api/v1/alerts/" + alertId + "/history", token).statusCode())
                .isEqualTo(200);

        assertNothingForbiddenIn(output, token, NOTE);
    }

    // ----------------------------------------------------------------------- //
    // Reporting
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("a report over a window that holds the alert logs nothing from inside it")
    void theReportingPathLeavesNothingForbidden(CapturedOutput output) {
        UUID transactionId = plantedTransaction();
        fixtures.insertAlert(transactionId, fixtures.insertAssessment(transactionId));
        String token = signIn();

        String window = "from=" + Instant.now().minus(Duration.ofDays(1)) + "&to="
                + Instant.now().plus(Duration.ofDays(1));

        assertThat(get("/api/v1/reports/alert-summary?" + window, token).statusCode())
                .isEqualTo(200);
        assertThat(get("/api/v1/reports/alerts.csv?" + window, token).statusCode())
                .isEqualTo(200);

        assertNothingForbiddenIn(output, token);
    }

    // ----------------------------------------------------------------------- //
    // Signing in
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("signing in logs who, and never what they typed")
    void signingInLeavesNoCredentialInTheLog(CapturedOutput output) {
        String token = signIn();

        // The failed attempt too: a refusal is the path most likely to log the
        // input that caused it, and it is the one an attacker can provoke.
        assertThat(post("/api/v1/auth/login", null, json(Map.of("username", ANALYST, "password", PASSWORD + "-wrong")))
                        .statusCode())
                .isEqualTo(401);

        assertThat(linesContaining(output.getAll(), ANALYST))
                .as("the username is how an operator's session is followed, so it is allowed and "
                        + "wanted; this asserts the line exists rather than that it does not")
                .isNotEmpty();
        assertNothingForbiddenIn(output, token);
    }

    // ----------------------------------------------------------------------- //
    // The backstop
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("printing a whole request is harmless, because the request redacts itself")
    void theObjectsThemselvesRefuseToPrintWhatIsForbidden() {
        // The backstop for the rule rather than the rule itself (ADR-0016 §4).
        // A record prints every component by default, so without these overrides
        // one log.debug("{}", request) anywhere would be a disclosure - and the
        // tests above would only catch it if that call site happened to run.
        TransactionRequest request = new TransactionRequest(
                IDEMPOTENCY_KEY,
                accountReference,
                merchantReference,
                TransactionType.PURCHASE,
                TransactionChannel.CARD_NOT_PRESENT,
                new AmountRequest(AMOUNT, "GBP"),
                "GB",
                DEVICE,
                Instant.now());

        String printed = request.toString();

        assertThat(printed).doesNotContain(AMOUNT).doesNotContain(DEVICE).doesNotContain(IDEMPOTENCY_KEY);
        // And still says which transaction, or the redaction has cost the log
        // the thing it was for.
        assertThat(printed).contains(accountReference).contains(merchantReference);
    }

    // ----------------------------------------------------------------------- //
    // Assertions
    // ----------------------------------------------------------------------- //

    /**
     * The whole captured stream, not one line of it.
     *
     * <p>{@code CapturedOutput} holds everything the JVM wrote to stdout and stderr during the test,
     * which is the point: a leak from a framework class nobody thought about is exactly the leak a
     * hand-picked logger assertion would miss.
     *
     * @param alsoForbidden values this path introduced — a bearer token, an analyst's note — beyond
     *     the three every path carries
     */
    private void assertNothingForbiddenIn(CapturedOutput output, String... alsoForbidden) {
        String written = output.getAll();

        Map<String, String> forbidden = new LinkedHashMap<>();
        forbidden.put(AMOUNT, "a monetary amount is forbidden in a log at every level (ADR-0016 §4)");
        forbidden.put(DEVICE, "a device handle identifies the instrument rather than the account");
        forbidden.put(
                IDEMPOTENCY_KEY,
                "an idempotency key is caller-controlled text, which is the injection surface "
                        + "CorrelationIdFilter already refuses to reflect");
        forbidden.put(PASSWORD, "a credential, and the one value on the list with no defensible reading");
        for (String value : alsoForbidden) {
            forbidden.putIfAbsent(
                    value, "introduced by this path: a bearer token or an actor's own words, both forbidden");
        }

        forbidden.forEach((value, why) ->
                assertThat(linesContaining(written, value)).as(why).isEmpty());

        // The positive half. A service that logged nothing would satisfy every
        // assertion above and be useless during an incident, so the correlation
        // identifier has to be there - it is what ties these lines to the
        // caller's request and to the scoring service's own log.
        assertThat(written)
                .as("every request carries a correlation id, and it is how one request is followed")
                .contains("correlationId");
    }

    /**
     * The offending lines, so a failure says which logger did it.
     *
     * <p>Asserting over the whole stream reports only that something somewhere leaked, and the
     * stream is tens of thousands of characters of Spring startup. A list of matching lines is the
     * difference between a failure somebody fixes and a failure somebody reruns.
     */
    private static List<String> linesContaining(String written, String forbidden) {
        return written.lines().filter(line -> line.contains(forbidden)).toList();
    }

    // ----------------------------------------------------------------------- //
    // Fixtures and requests
    // ----------------------------------------------------------------------- //

    /**
     * A stored transaction carrying the planted amount.
     *
     * <p>Written directly rather than posted, because the read-path tests need the amount to be in
     * the database before the request they are asserting about is made — posting it first would put
     * the ingestion path's log lines into the same captured stream and make a failure ambiguous
     * about which half produced it.
     */
    private UUID plantedTransaction() {
        return jdbc.queryForObject(
                """
                INSERT INTO transactions (
                    transaction_reference, idempotency_key, account_id, merchant_id,
                    type, channel, amount, currency, origin_country, device_reference,
                    occurred_at, ingestion_source, processing_status, correlation_id)
                VALUES (?, ?, ?, ?, 'PURCHASE', 'CARD_NOT_PRESENT', ?::numeric, 'GBP', 'GB', ?,
                        now(), 'API', 'ASSESSED', gen_random_uuid())
                RETURNING id
                """,
                UUID.class,
                SchemaFixtures.nextTransactionReference(jdbc),
                "planted-" + SchemaFixtures.next6(),
                accountId,
                merchantId,
                AMOUNT,
                DEVICE);
    }

    /**
     * One analyst with a real credential, hashed by the application's own encoder.
     *
     * <p>Through the encoder rather than as a literal: a hard-coded hash would be a published
     * credential, and one produced by a different encoder than the login path uses would make the
     * sign-in assertion prove nothing about the login path.
     */
    private UUID operatorWithPassword() {
        Integer existing = jdbc.queryForObject("SELECT count(*) FROM users WHERE username = ?", Integer.class, ANALYST);
        if (existing == null || existing == 0) {
            jdbc.update(
                    "INSERT INTO users (username, display_name, status) VALUES (?, ?, 'ACTIVE')",
                    ANALYST,
                    "Redaction test analyst");
            jdbc.update("""
                    INSERT INTO user_roles (user_id, role_id)
                    SELECT u.id, r.id FROM users u, roles r WHERE u.username = ? AND r.code = ?
                    """, ANALYST, RoleCode.ANALYST.name());
            jdbc.update("""
                    INSERT INTO user_credentials (user_id, password_hash)
                    SELECT u.id, ? FROM users u WHERE u.username = ?
                    """, passwordEncoder.encode(PASSWORD), ANALYST);
        }
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", UUID.class, ANALYST);
    }

    /** Signs in over HTTP with the real password, and returns the token that came back. */
    private String signIn() {
        HttpResponse<String> response =
                post("/api/v1/auth/login", null, json(Map.of("username", ANALYST, "password", PASSWORD)));
        assertThat(response.statusCode()).isEqualTo(200);

        String body = response.body();
        int start = body.indexOf("\"token\":\"") + "\"token\":\"".length();
        int end = body.indexOf('"', start);
        assertThat(start).as("the login response carries a token: %s", body).isGreaterThan(8);
        return body.substring(start, end);
    }

    /** One request body, as JSON text, so nothing between here and the socket renders it. */
    private static String json(Map<String, Object> fields) {
        StringBuilder out = new StringBuilder("{");
        fields.forEach((key, value) -> {
            if (out.length() > 1) {
                out.append(',');
            }
            out.append('"').append(key).append("\":");
            if (value instanceof Number) {
                out.append(value);
            } else {
                out.append('"').append(value).append('"');
            }
        });
        return out.append('}').toString();
    }

    /** One transaction submission, as JSON text. */
    private String body(String idempotencyKey, String amount, String originCountry) {
        return """
                {
                  "idempotencyKey": "%s",
                  "accountReference": "%s",
                  "merchantReference": "%s",
                  "type": "PURCHASE",
                  "channel": "CARD_NOT_PRESENT",
                  "amount": {"value": "%s", "currency": "GBP"},
                  "originCountry": "%s",
                  "deviceReference": "%s",
                  "occurredAt": "%s"
                }
                """.formatted(
                        idempotencyKey,
                        accountReference,
                        merchantReference,
                        amount,
                        originCountry,
                        DEVICE,
                        Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
    }

    private HttpResponse<String> post(String json) {
        return post("/api/v1/transactions", null, json);
    }

    private HttpResponse<String> get(String path, String token) {
        return send(request(path, token).GET());
    }

    private HttpResponse<String> post(String path, String token, String json) {
        return send(request(path, token).POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    private HttpResponse<String> put(String path, String token, String json) {
        return send(request(path, token).PUT(HttpRequest.BodyPublishers.ofString(json)));
    }

    private HttpRequest.Builder request(String path, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        return token == null ? builder : builder.header("Authorization", "Bearer " + token);
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) {
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException failed) {
            throw new AssertionError("the request never reached the application", failed);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while sending", interrupted);
        }
    }
}
