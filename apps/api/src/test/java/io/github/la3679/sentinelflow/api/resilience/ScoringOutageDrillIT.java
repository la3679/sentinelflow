/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.github.la3679.sentinelflow.api.support.AbstractPostgresTest;
import io.github.la3679.sentinelflow.api.support.KafkaContainerSupport;
import io.github.la3679.sentinelflow.api.support.SchemaFixtures;
import io.github.la3679.sentinelflow.api.support.TestCredentials;
import io.github.la3679.sentinelflow.api.web.ApiHeaders;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Drill 1 — the scoring service goes away, and the pipeline carries on without it.
 *
 * <p>This is a <strong>drill</strong> rather than a unit test, and the distinction is the point of
 * the file. {@code ScoringClientTests} already drives one client through a timeout, a refusal and a
 * 503 and asserts that the breaker opens; nothing anywhere asserted the consequence ADR-0008 §2 and
 * §3 actually promise, which is a property of the <em>system</em>: while scoring is down every
 * transaction still gets an assessment, every one of them says it is degraded, none is lost or
 * dead-lettered, and the cost of the outage does not grow with the number of transactions. That last
 * clause is the breaker's reason for existing and it cannot be observed anywhere smaller than this.
 *
 * <h2>Everything here runs on the real path</h2>
 *
 * A transaction is posted to {@code /api/v1/transactions} over HTTP, committed with its outbox row,
 * picked up by the relay, published to a real broker, consumed by the real listener, and assessed by
 * the real ruleset. The only thing standing in for something else is the scoring service, whose
 * status code the drill controls — and that is the thing being failed, so it has to be.
 *
 * <h2>The outage is a 503, and it is worth saying why</h2>
 *
 * A stopped container answers with a connection refusal, a hung one answers with nothing at all, and
 * a service that is up but unwell answers 503. {@code ScoringClient} maps all three to
 * {@code ScoringUnavailableException} — asserted per transport in {@code ScoringClientTests} — so
 * nothing below that point can tell them apart, and this drill exercises what the pipeline does
 * rather than re-testing the classification. A 503 is the one outage a test can start and stop with
 * no port to rebind and no blocked thread to release, and a drill that is flaky is a drill nobody
 * runs.
 *
 * <h2>The breaker is left at its shipped threshold</h2>
 *
 * {@code RiskAssessmentWorkflowIT} raises the threshold to 1000 so a degradation test cannot leak
 * into the next method's scored path. That is right for a suite of independent assertions and wrong
 * here: five consecutive failures, the number {@code application.yaml} ships, is the subject. Only
 * the open window is shortened, from thirty seconds to five, because the recovery phase waits it
 * out.
 *
 * <h2>One test method, three phases, in order</h2>
 *
 * A drill is a sequence — the recovery assertion means nothing unless the outage happened first —
 * and three methods would need either an execution order or a shared static that makes each depend
 * on the last having run. One method that says which phase it is in is the honest shape.
 */
@Import(KafkaContainerSupport.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
        properties = {
            // The real path, both halves of it: the relay publishes what
            // ingestion committed, and the listener consumes it.
            "sentinelflow.outbox.enabled=true",
            "sentinelflow.outbox.poll-interval=200ms",
            "sentinelflow.consumer.enabled=true",
            // The broker readings are irrelevant here and their scheduled admin
            // calls only add noise to the output.
            "sentinelflow.observability.kafka.enabled=false",
            // The scoring budget, compressed. The shipped one is a second to
            // connect and two to read, three attempts deep; against a stub on
            // loopback that is time spent proving nothing, and it would make the
            // outage phase alone take a minute.
            "sentinelflow.scoring.client.connect-timeout=250ms",
            "sentinelflow.scoring.client.read-timeout=500ms",
            "sentinelflow.scoring.client.retry-base=1ms",
            "sentinelflow.scoring.client.retry-max-delay=5ms",
            // Shipped threshold, deliberately. See the class comment.
            "sentinelflow.scoring.client.circuit-breaker-failure-threshold=5",
            "sentinelflow.scoring.client.circuit-breaker-open-duration=5s"
        })
class ScoringOutageDrillIT extends AbstractPostgresTest {

    /** How many transactions each phase posts. */
    private static final int HEALTHY_BEFORE = 3;

    private static final int DURING_OUTAGE = 30;
    private static final int HEALTHY_AFTER = 3;

    /** Matches {@code circuit-breaker-failure-threshold} above. */
    private static final int FAILURE_THRESHOLD = 5;

    /** Matches {@code max-retries} in {@code application.yaml}: two retries, so three attempts. */
    private static final int ATTEMPTS_PER_CALL = 3;

    /** Matches {@code circuit-breaker-open-duration} above. */
    private static final Duration OPEN_WINDOW = Duration.ofSeconds(5);

    private static final Duration TIMEOUT = Duration.ofSeconds(90);

    private static final String CALLS_METRIC = "sentinelflow.scoring.calls";
    private static final String BREAKER_METRIC = "sentinelflow.scoring.breaker.state";

    private static final String SCORED_BODY = """
            {
              "modelVersion": "1.0.0",
              "featureVersion": "1.0.0",
              "modelScore": 41.5,
              "reasons": [{"code": "HISTORY_SIZE_LOW", "contribution": -1.2}],
              "inferenceDurationMs": 3.5,
              "warnings": []
            }
            """;

    private static final String UNAVAILABLE_BODY = "{\"title\":\"Service Unavailable\"}";

    /**
     * The stub, started before the context so its port can be bound into the client's base URL.
     *
     * <p>Static for the reason {@link DynamicPropertySource} forces: it runs once per context, and a
     * per-test server would listen on a port the {@code RestClient} was never built with.
     */
    private static final HttpServer SCORING = startScoringStub();

    /** 200 while scoring is healthy, 503 while it is not. The drill's one lever. */
    private static final AtomicInteger STATUS = new AtomicInteger(200);

    /** Every request that reached the stub. The number the breaker's promise is measured against. */
    private static final AtomicInteger REQUESTS = new AtomicInteger();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    private RestTestClient client;
    private SchemaFixtures fixtures;
    private String merchantReference;

    @DynamicPropertySource
    static void scoringBaseUrl(DynamicPropertyRegistry registry) {
        registry.add(
                "sentinelflow.scoring.client.base-url",
                () -> "http://127.0.0.1:" + SCORING.getAddress().getPort());
    }

    private static HttpServer startScoringStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/score", ScoringOutageDrillIT::respond);
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot start the scoring stub", e);
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        REQUESTS.incrementAndGet();
        exchange.getRequestBody().readAllBytes();

        int status = STATUS.get();
        byte[] body = (status == 200 ? SCORED_BODY : UNAVAILABLE_BODY).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @BeforeEach
    void setUp() {
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
        fixtures = new SchemaFixtures(jdbc);
        merchantReference = jdbc.queryForObject(
                "SELECT merchant_reference FROM merchants WHERE id = ?", String.class, fixtures.insertMerchant());
        STATUS.set(200);
    }

    @AfterAll
    static void stopStub() {
        STATUS.set(200);
        SCORING.stop(0);
    }

    @Test
    @DisplayName("drill: scoring goes down, every transaction is still assessed, and recovery is automatic")
    void survivesAScoringOutage() {
        // ------------------------------------------------------------------ //
        // Phase 1 - healthy. Establishes that the path under test works at all,
        // so a degraded assessment in phase 2 is evidence about the outage
        // rather than about the wiring.
        // ------------------------------------------------------------------ //
        List<UUID> before = postTransactions(HEALTHY_BEFORE);
        awaitAssessed(before);

        assertThat(degradedCount(before))
                .as("nothing is degraded while scoring is answering; if this fails the drill proves nothing")
                .isZero();

        // ------------------------------------------------------------------ //
        // Phase 2 - the outage.
        // ------------------------------------------------------------------ //
        int requestsBeforeOutage = REQUESTS.get();
        STATUS.set(503);

        List<UUID> during = postTransactions(DURING_OUTAGE);
        awaitAssessed(during);

        assertThat(degradedCount(during))
                .as("every assessment written while scoring was down says so; a silently worse score "
                        + "is the failure mode ADR-0008 section 2 exists to prevent")
                .isEqualTo(DURING_OUTAGE);
        assertThat(modelScoresPresent(during))
                .as("a degraded assessment states no model score at all - a zero would be a claim "
                        + "about the transaction, and no such claim was made")
                .isZero();
        assertThat(failedCount(during))
                .as("scoring being unavailable is a condition to absorb, never a record to dead-letter")
                .isZero();
        assertThat(processingStatuses(during))
                .as("every transaction reached a terminal, correct state")
                .containsOnly("ASSESSED");

        assertThat(breakerGauge("OPEN"))
                .as("five consecutive failures is the shipped threshold and thirty records crossed it")
                .isEqualTo(1.0d);
        assertThat(callCount("breaker_open"))
                .as("the records that arrived after the breaker opened were degraded without an attempt")
                .isGreaterThanOrEqualTo((double) (DURING_OUTAGE - FAILURE_THRESHOLD - 1));

        // The claim the breaker exists to support, stated as arithmetic rather
        // than as a wall-clock measurement: without it, thirty records would
        // have cost thirty full call budgets - ninety HTTP attempts - and the
        // consumer would have held its partition for every one of them. With it,
        // the outage costs five records' worth. The allowance of two further
        // records' worth is for half-open probes: the open window is five
        // seconds, and a slow machine can take longer than that to work through
        // thirty transactions.
        int outageRequests = REQUESTS.get() - requestsBeforeOutage;
        assertThat(outageRequests)
                .as("HTTP attempts during the outage are bounded by the breaker, not by the number "
                        + "of transactions")
                .isBetween(ATTEMPTS_PER_CALL, ATTEMPTS_PER_CALL * (FAILURE_THRESHOLD + 2));
        assertThat(outageRequests)
                .as(
                        "and are far below the %d attempts an unguarded pipeline would have made",
                        DURING_OUTAGE * ATTEMPTS_PER_CALL)
                .isLessThan(DURING_OUTAGE * ATTEMPTS_PER_CALL);

        // ------------------------------------------------------------------ //
        // Phase 3 - recovery, with nothing restarted and nothing reset. The
        // breaker's open window elapses, one probe is let through, and the
        // pipeline scores again on its own.
        // ------------------------------------------------------------------ //
        STATUS.set(200);

        // The recovery batch is posted after the open window has elapsed, not
        // the instant scoring comes back, and the reason is worth keeping: the
        // breaker only reconsiders when a record asks it to, and an assessment
        // that has already been written is never rewritten. A batch posted
        // immediately would be consumed while the breaker was still open and
        // would be permanently degraded - which is correct behaviour and would
        // have looked like a broken recovery. Awaitility's pollDelay is the
        // wait; a bare sleep would be the same thing with less to read.
        await().pollDelay(OPEN_WINDOW.plusSeconds(1)).atMost(TIMEOUT).until(() -> true);

        List<UUID> after = postTransactions(HEALTHY_AFTER);
        awaitAssessed(after);

        assertThat(degradedCount(after))
                .as("one probe was let through, it succeeded, and the pipeline scores again - with "
                        + "nothing restarted, nothing reset, and no operator action")
                .isZero();
        assertThat(breakerGauge("CLOSED")).isEqualTo(1.0d);
        assertThat(breakerGauge("OPEN")).isEqualTo(0.0d);
    }

    // ----------------------------------------------------------------------- //
    // Posting, and waiting for the pipeline to catch up
    // ----------------------------------------------------------------------- //

    /**
     * Posts {@code count} transactions, each on its own account.
     *
     * <p>A fresh account per transaction, deliberately. Four transactions on one account inside five
     * minutes fire {@code VELOCITY_5M_HIGH} and a fifth from elsewhere adds {@code COUNTRY_CHANGE},
     * so a shared account would make this drill raise alerts and band differently depending on how
     * fast the loop ran. The subject here is degradation, not scoring arithmetic.
     *
     * @return the ids of the transactions that were stored, in the order they were posted
     */
    private List<UUID> postTransactions(int count) {
        List<UUID> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String accountReference = jdbc.queryForObject(
                    "SELECT account_reference FROM accounts WHERE id = ?",
                    String.class,
                    fixtures.insertAccount(fixtures.insertCustomer()));
            String key = "drill-" + SchemaFixtures.next6();

            client.post()
                    .uri("/api/v1/transactions")
                    .header(ApiHeaders.API_KEY, TestCredentials.INGEST_API_KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "idempotencyKey",
                            key,
                            "accountReference",
                            accountReference,
                            "merchantReference",
                            merchantReference,
                            "type",
                            "PURCHASE",
                            "channel",
                            "CARD_NOT_PRESENT",
                            "amount",
                            Map.of("value", "88.10", "currency", "GBP"),
                            "originCountry",
                            "GB",
                            "occurredAt",
                            Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()))
                    .exchange()
                    .expectStatus()
                    .isAccepted();

            ids.add(jdbc.queryForObject("SELECT id FROM transactions WHERE idempotency_key = ?", UUID.class, key));
        }
        return ids;
    }

    private void awaitAssessed(List<UUID> transactionIds) {
        await().atMost(TIMEOUT)
                .pollInterval(Duration.ofMillis(200))
                .until(() -> assessmentCount(transactionIds) == transactionIds.size());
    }

    private int assessmentCount(List<UUID> transactionIds) {
        return count("SELECT count(*) FROM risk_assessments WHERE transaction_id IN (%s)", transactionIds);
    }

    private int degradedCount(List<UUID> transactionIds) {
        return count("SELECT count(*) FROM risk_assessments WHERE degraded AND transaction_id IN (%s)", transactionIds);
    }

    private int modelScoresPresent(List<UUID> transactionIds) {
        return count(
                "SELECT count(*) FROM risk_assessments WHERE model_score IS NOT NULL AND transaction_id IN (%s)",
                transactionIds);
    }

    private int failedCount(List<UUID> transactionIds) {
        return count(
                "SELECT count(*) FROM transactions WHERE processing_status = 'FAILED' AND id IN (%s)", transactionIds);
    }

    private List<String> processingStatuses(List<UUID> transactionIds) {
        return jdbc.queryForList(
                "SELECT processing_status FROM transactions WHERE id IN (" + placeholders(transactionIds) + ")",
                String.class,
                transactionIds.toArray());
    }

    private int count(String template, List<UUID> transactionIds) {
        return jdbc.queryForObject(
                String.format(template, placeholders(transactionIds)), Integer.class, transactionIds.toArray());
    }

    /**
     * One {@code ?} per identifier.
     *
     * <p>An {@code IN} list of placeholders rather than {@code = ANY (?)} with a bound array. The
     * array form needs the driver to encode a {@code UUID[]}, which is a driver behaviour this
     * project has no reason to depend on in a test; the placeholders are still bound parameters, so
     * nothing here builds SQL out of a value.
     */
    private static String placeholders(List<UUID> ids) {
        return String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
    }

    private double callCount(String outcome) {
        return meters.find(CALLS_METRIC).tag("outcome", outcome).counter().count();
    }

    private double breakerGauge(String state) {
        return meters.find(BREAKER_METRIC).tag("state", state).gauge().value();
    }
}
