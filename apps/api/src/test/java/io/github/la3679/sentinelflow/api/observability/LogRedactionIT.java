/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
import org.springframework.test.context.TestPropertySource;

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
 * only honest way to check it is to drive a real request through the real application and read what
 * came out. A unit test would format a line itself and search it, which proves that whoever wrote
 * the test picked a safe example. What is asserted here is every line that ingestion, validation,
 * the outbox and the framework wrote, together.
 *
 * <h2>DEBUG, on purpose</h2>
 *
 * An assertion that holds only because the application ships at {@code INFO} is an assertion about
 * configuration, and configuration is a thing a deployment changes. This runs the application's own
 * package and Spring's web layer at {@code DEBUG} so the quiet lines are in scope too.
 *
 * <p>Hibernate's {@code org.hibernate.orm.jdbc.bind} logger stays where the shipped configuration
 * puts it. That one prints every bound parameter of every statement by design — it exists to do
 * exactly what this test forbids — and turning it on would be testing whether a debugging tool is a
 * debugging tool. The line that made it matter, {@code org.hibernate.orm.jdbc.error}, is pinned to
 * {@code ERROR} in {@code application.yaml} with the reason written beside it.
 *
 * <h2>The planted values</h2>
 *
 * Distinctive enough that a match cannot be coincidence. A plausible amount like {@code 12.00}
 * turns up inside a timestamp or a duration eventually; {@code 4242.4242} does not.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(
        properties = {"logging.level.io.github.la3679.sentinelflow=DEBUG", "logging.level.org.springframework.web=DEBUG"
        })
class LogRedactionIT extends AbstractPostgresTest {

    /** A monetary amount. Forbidden in a log at every level. */
    private static final String AMOUNT = "4242.4242";

    /** A device handle. Forbidden for the same reason. */
    private static final String DEVICE = "DEV-beefbeefbeef";

    /** Caller-controlled text, which is also the log-injection surface. */
    private static final String IDEMPOTENCY_KEY = "redaction-key-cafebabe-8f2a41d7";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * The JDK's client, deliberately, rather than {@code RestTestClient}.
     *
     * <p>This test turns {@code org.springframework.web} up to {@code DEBUG}, and Spring's
     * {@code RestClient} logs the body it is about to write at that level. Sending the request with
     * one puts the amount, the device handle and the idempotency key into the captured stream from
     * <strong>the test's own client</strong> — which is what the first version of this test did, and
     * it failed for a reason that had nothing to do with the application.
     *
     * <p>With the JDK's client, a {@code DefaultRestClient} line in this output can only have come
     * from the application's own outbound call, which is exactly what these assertions should be
     * allowed to catch.
     */
    private final HttpClient http = HttpClient.newBuilder().build();

    private String accountReference;
    private String merchantReference;

    @BeforeEach
    void setUp() {
        SchemaFixtures fixtures = new SchemaFixtures(jdbc);
        UUID customerId = fixtures.insertCustomer();
        UUID accountId = fixtures.insertAccount(customerId);
        UUID merchantId = fixtures.insertMerchant();
        accountReference =
                jdbc.queryForObject("SELECT account_reference FROM accounts WHERE id = ?", String.class, accountId);
        merchantReference =
                jdbc.queryForObject("SELECT merchant_reference FROM merchants WHERE id = ?", String.class, merchantId);
    }

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

    /**
     * The whole captured stream, not one line of it.
     *
     * <p>{@code CapturedOutput} holds everything the JVM wrote to stdout and stderr during the test,
     * which is the point: a leak from a framework class nobody thought about is exactly the leak a
     * hand-picked logger assertion would miss.
     */
    private void assertNothingForbiddenIn(CapturedOutput output) {
        String written = output.getAll();

        assertThat(linesContaining(written, AMOUNT))
                .as("a monetary amount is forbidden in a log at every level (ADR-0016 §4)")
                .isEmpty();
        assertThat(linesContaining(written, DEVICE))
                .as("a device handle identifies the instrument rather than the account")
                .isEmpty();
        assertThat(linesContaining(written, IDEMPOTENCY_KEY))
                .as("an idempotency key is caller-controlled text, which is the injection surface "
                        + "CorrelationIdFilter already refuses to reflect")
                .isEmpty();

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

    /** One request, as JSON text, so nothing between here and the socket renders it. */
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
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/transactions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException failed) {
            throw new AssertionError("the request never reached the application", failed);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while posting", interrupted);
        }
    }
}
