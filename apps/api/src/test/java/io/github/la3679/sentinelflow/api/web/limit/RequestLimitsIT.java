/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web.limit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import io.github.la3679.sentinelflow.api.support.AbstractPostgresTest;
import io.github.la3679.sentinelflow.api.support.SchemaFixtures;
import io.github.la3679.sentinelflow.api.support.TestCredentials;
import io.github.la3679.sentinelflow.api.web.ApiHeaders;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * The three controls ADR-0017 adds, driven over HTTP against the real application.
 *
 * <h2>Why this suite sets its own limits</h2>
 *
 * {@code AbstractPostgresTest} raises every allowance out of the way, so a suite about idempotency
 * fails on idempotency rather than on a bucket it was never meant to reach. This one sets them
 * deliberately low, because it is the suite that is about them. The numbers here are chosen to be
 * reachable in a test, not to resemble the defaults.
 *
 * <h2>What is asserted, and why each is not obvious</h2>
 *
 * <ul>
 *   <li><strong>The credential</strong>: absent, wrong and correct, and that the key does not open
 *       the read endpoint beside it.
 *   <li><strong>The limit</strong>: that it refuses, that the refusal is the shape the contract
 *       promises, and that two callers do not share one allowance — that last one is the difference
 *       between a rate limiter and an outage.
 *   <li><strong>The size cap</strong>: both halves, the declared length and the delivered bytes,
 *       because either alone would be a bound a caller opts into.
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
        properties = {
            // Low enough to reach inside a test, and each category set
            // separately so a test can prove they do not share a bucket.
            "sentinelflow.limits.ingest.permits=3",
            "sentinelflow.limits.ingest.per=1m",
            "sentinelflow.limits.ingest.burst=3",
            "sentinelflow.limits.login.permits=2",
            "sentinelflow.limits.login.per=1m",
            "sentinelflow.limits.login.burst=2",
            "sentinelflow.limits.standard.permits=1000",
            "sentinelflow.limits.standard.burst=1000",
            // Small enough that a body can exceed it without the test writing a
            // megabyte of JSON into a failure message.
            "sentinelflow.limits.max-request-bytes=2048"
        })
class RequestLimitsIT extends AbstractPostgresTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RateLimiter limiter;

    @Autowired
    private MeterRegistry meters;

    private RestTestClient client;
    private String accountReference;
    private String merchantReference;

    @BeforeEach
    void setUp() {
        // The limiter is a singleton and its state is the point, so one method's
        // spent allowance would otherwise be the next one's starting position
        // and the results would depend on the order JUnit picked.
        limiter.clear();

        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        SchemaFixtures fixtures = new SchemaFixtures(jdbc);
        UUID accountId = fixtures.insertAccount(fixtures.insertCustomer());
        UUID merchantId = fixtures.insertMerchant();
        accountReference =
                jdbc.queryForObject("SELECT account_reference FROM accounts WHERE id = ?", String.class, accountId);
        merchantReference =
                jdbc.queryForObject("SELECT merchant_reference FROM merchants WHERE id = ?", String.class, merchantId);
    }

    // ----------------------------------------------------------------------- //
    // The ingestion credential (ADR-0017 §1)
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("a transaction posted without the key is refused, and the endpoint is no longer open")
    void refusesIngestionWithoutTheKey() {
        client.post()
                .uri("/api/v1/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body(key()))
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    @DisplayName("a wrong key is refused in the same words as an absent one, saying nothing about which")
    void refusesAWrongKeyIdentically() {
        String absent = problemFrom(client.post()
                .uri("/api/v1/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body(key()))
                .exchange()
                .expectStatus()
                .isUnauthorized());

        String wrong = problemFrom(client.post()
                .uri("/api/v1/transactions")
                .header(ApiHeaders.API_KEY, "a-wrong-key-of-entirely-sufficient-length")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body(key()))
                .exchange()
                .expectStatus()
                .isUnauthorized());

        // Compared rather than asserted one at a time: two refusals that happen
        // to both be 401 but read differently are an oracle, and only a
        // comparison catches that.
        assertThat(wrong).isEqualTo(absent);
    }

    @Test
    @DisplayName("the right key is accepted, so the refusals above are about the credential and not the payload")
    void acceptsTheConfiguredKey() {
        ingest(key()).expectStatus().isAccepted();
    }

    @Test
    @DisplayName("the ingestion key does not open the read endpoint beside it")
    void doesNotGrantReads() {
        client.get()
                .uri("/api/v1/transactions")
                .header(ApiHeaders.API_KEY, TestCredentials.INGEST_API_KEY)
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    // ----------------------------------------------------------------------- //
    // The rate limit (ADR-0017 §2)
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("a caller past its ingestion allowance is refused with 429 and told when to retry")
    void refusesBeyondTheIngestionAllowance() {
        for (int accepted = 0; accepted < 3; accepted++) {
            ingest(key()).expectStatus().isAccepted();
        }

        client.post()
                .uri("/api/v1/transactions")
                .header(ApiHeaders.API_KEY, TestCredentials.INGEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body(key()))
                .exchange()
                .expectStatus()
                .isEqualTo(429)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader()
                .exists("Retry-After");
    }

    @Test
    @DisplayName("the refusal is a problem document, and it says nothing about the limit it hit")
    void refusesInTheShapeTheContractPromises() {
        for (int accepted = 0; accepted < 3; accepted++) {
            ingest(key()).expectStatus().isAccepted();
        }

        String problem = problemFrom(client.post()
                .uri("/api/v1/transactions")
                .header(ApiHeaders.API_KEY, TestCredentials.INGEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body(key()))
                .exchange()
                .expectStatus()
                .isEqualTo(429));

        assertThat(problem).contains("\"status\":429").contains("rate-limited");
        // No allowance, no remaining count, no window. A caller being limited
        // needs to know to come back later, not how close they got.
        assertThat(problem).doesNotContain("\"limit\"").doesNotContain("remaining");
    }

    @Test
    @DisplayName("a refused request is still counted by the actuator, so a 429 rate is observable")
    void isVisibleToTheActuator() {
        for (int accepted = 0; accepted < 3; accepted++) {
            ingest(key()).expectStatus().isAccepted();
        }
        ingest(key()).expectStatus().isEqualTo(429);

        // The limiter refuses inside a filter, before the dispatcher. Whether
        // Spring's own request metric sees that depends on filter ordering, and
        // RUNBOOKS.md Runbook 10 tells an operator to graph it - so this asserts
        // the series exists rather than leaving the runbook to be wrong quietly.
        assertThat(meters.find("http.server.requests").tag("status", "429").timer())
                .isNotNull();
    }

    @Test
    @DisplayName("a second caller has its own allowance, which is what makes this a limit and not an outage")
    void countsCallersSeparately() {
        for (int accepted = 0; accepted < 3; accepted++) {
            ingest(key()).expectStatus().isAccepted();
        }
        ingest(key()).expectStatus().isEqualTo(429);

        // A different key is a different caller. It is refused for having the
        // wrong credential (401) rather than for being over an allowance (429),
        // which is exactly the distinction being asserted: the first caller's
        // spent bucket did not become everybody's.
        client.post()
                .uri("/api/v1/transactions")
                .header(ApiHeaders.API_KEY, "a-second-caller-key-of-sufficient-length")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body(key()))
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    @DisplayName("the login allowance is its own, so a spent one does not close the rest of the API")
    void separatesLoginFromEverythingElse() {
        for (int attempt = 0; attempt < 2; attempt++) {
            login().expectStatus().isUnauthorized();
        }
        login().expectStatus().isEqualTo(429);

        // The same caller, a different category, still served. Two thousand
        // reads are allowed by this suite's configuration and one is enough to
        // show the buckets are not shared.
        client.get().uri("/api/v1/alerts").exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("the actuator is not rate limited, because refusing a health probe is how a limiter causes an outage")
    void doesNotLimitTheActuator() {
        for (int probe = 0; probe < 20; probe++) {
            client.get().uri("/actuator/health").exchange().expectStatus().isOk();
        }
    }

    // ----------------------------------------------------------------------- //
    // The request size cap (ADR-0017 §3)
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("a body larger than the cap is refused with 413 before it is parsed")
    void refusesAnOversizedBody() {
        Map<String, Object> oversized = body(key());
        oversized.put("padding", "x".repeat(4096));

        client.post()
                .uri("/api/v1/transactions")
                .header(ApiHeaders.API_KEY, TestCredentials.INGEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .body(oversized)
                .exchange()
                .expectStatus()
                .isEqualTo(413)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    @DisplayName("the refusal names the maximum, because a limit a client cannot discover is one it will hit again")
    void saysWhatTheMaximumIs() {
        Map<String, Object> oversized = body(key());
        oversized.put("padding", "x".repeat(4096));

        String problem = problemFrom(client.post()
                .uri("/api/v1/transactions")
                .header(ApiHeaders.API_KEY, TestCredentials.INGEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .body(oversized)
                .exchange()
                .expectStatus()
                .isEqualTo(413));

        assertThat(problem).contains("2048").contains(RequestSizeLimitFilter.PROBLEM_TYPE);
    }

    @Test
    @DisplayName("a body under the cap is unaffected, so the cap is a bound rather than a break")
    void acceptsABodyUnderTheCap() {
        ingest(key()).expectStatus().isAccepted();
    }

    @Test
    @DisplayName("a chunked body that declares no length is cut off at the same number")
    void refusesAnOversizedChunkedBody() throws Exception {
        // The half a Content-Length check cannot reach. BodyPublishers.ofInputStream
        // sends chunked, with no declared length at all, so the only thing that
        // can stop this is the wrapped stream. Without it the cap would be a
        // bound a caller opts into by declaring one honestly.
        byte[] oversized = ("{\"padding\":\"" + "x".repeat(8192) + "\"}").getBytes(StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/transactions"))
                .header("Content-Type", "application/json")
                .header(ApiHeaders.API_KEY, TestCredentials.INGEST_API_KEY)
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(oversized)))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains(RequestSizeLimitFilter.PROBLEM_TYPE);
    }

    // ----------------------------------------------------------------------- //
    // Helpers
    // ----------------------------------------------------------------------- //

    private RestTestClient.ResponseSpec ingest(String idempotencyKey) {
        return client.post()
                .uri("/api/v1/transactions")
                .header(ApiHeaders.API_KEY, TestCredentials.INGEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body(idempotencyKey))
                .exchange();
    }

    private RestTestClient.ResponseSpec login() {
        return client.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "nobody.here", "password", "not-the-password"))
                .exchange();
    }

    private static String key() {
        return "limits-" + SchemaFixtures.next6();
    }

    private Map<String, Object> body(String idempotencyKey) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("idempotencyKey", idempotencyKey);
        body.put("accountReference", accountReference);
        body.put("merchantReference", merchantReference);
        body.put("type", "PURCHASE");
        body.put("channel", "CARD_NOT_PRESENT");
        body.put("amount", Map.of("value", "12.34", "currency", "GBP"));
        body.put("originCountry", "GB");
        body.put("occurredAt", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
        return body;
    }

    private static String problemFrom(RestTestClient.ResponseSpec response) {
        return response.expectBody(String.class).returnResult().getResponseBody();
    }
}
