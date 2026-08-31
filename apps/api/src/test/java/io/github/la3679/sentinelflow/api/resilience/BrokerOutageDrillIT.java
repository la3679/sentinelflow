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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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

import io.github.la3679.sentinelflow.api.messaging.consumer.TransactionCreatedConsumer;
import io.github.la3679.sentinelflow.api.support.AbstractPostgresTest;
import io.github.la3679.sentinelflow.api.support.KafkaContainerSupport;
import io.github.la3679.sentinelflow.api.support.SchemaFixtures;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Drill 2 — the broker goes away mid-run, and nothing is lost, duplicated, or refused.
 *
 * <p>This is the drill the outbox exists for. ADR-0005 chose a transactional outbox over publishing
 * inside the request, and the whole argument for paying its latency is a claim about an outage:
 * ingestion keeps accepting while the broker is unreachable, the events wait in a table rather than
 * evaporating, and when the broker returns the backlog drains exactly once. Until this file, every
 * part of that had been asserted against a publisher that fails on command
 * ({@code OutboxRelayIT}) — which proves the relay's policy and proves nothing about the broker.
 *
 * <h2>What "the broker goes away" means here</h2>
 *
 * {@link KafkaContainerSupport#pauseBroker()} freezes the container's processes. Its comment records
 * why that rather than a stop and start — Docker re-picks an ephemeral host port on start, so the
 * broker that came back would be at an address nothing in this context is configured for. A frozen
 * broker is a broker that stops answering, which is what a stalled disk or a one-sided network
 * partition looks like from here, and it is the failure the producer's delivery timeout is written
 * against.
 *
 * <h2>Three budgets are compressed, and one is not</h2>
 *
 * The producer's delivery timeout, the relay's poll interval and its retry backoff are all shortened
 * so an outage costs seconds rather than minutes; the shipped values are in {@code application.yaml}
 * with the reasoning beside them. {@code max-attempts} is <em>raised</em> instead, to keep the
 * shipped meaning intact: ten attempts is about twenty-five minutes of real backoff, and with the
 * backoff compressed it would be spent inside this drill and rows would give up during an outage
 * they are supposed to survive.
 *
 * <p>{@code batch-size} is one. The relay's drain is a single transaction over the whole batch, so
 * with the shipped size of a hundred no failure is visible until every claimed row has been
 * attempted and the transaction commits. One row per drain makes the first failure observable in one
 * attempt, which is what lets this test assert on it rather than sleep through it.
 */
@Import(KafkaContainerSupport.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
        properties = {
            "sentinelflow.outbox.enabled=true",
            "sentinelflow.outbox.poll-interval=200ms",
            "sentinelflow.outbox.batch-size=1",
            "sentinelflow.outbox.retry-base=200ms",
            "sentinelflow.outbox.retry-max-delay=1s",
            "sentinelflow.outbox.max-attempts=40",
            "sentinelflow.consumer.enabled=true",
            // Off: its admin calls would spend their five-second budget on a
            // frozen broker every fifteen seconds and add nothing this drill
            // asserts on. What the outage does to consumer lag is a dashboard
            // question, and the dashboards were evidenced in PR #73.
            "sentinelflow.observability.kafka.enabled=false",
            // The producer, compressed. Shipped: 20s delivery, 10s request. A
            // send against a frozen broker spends the whole delivery timeout
            // before it fails, and the relay attempts rows one at a time, so
            // this number is very nearly the cost of the drill.
            "spring.kafka.producer.properties.delivery.timeout.ms=1500",
            "spring.kafka.producer.properties.request.timeout.ms=700",
            "spring.kafka.producer.properties.max.block.ms=1500",
            "spring.kafka.producer.properties.linger.ms=0",
            // Scoring is not the subject here; it answers so that consumption
            // reaches a written assessment rather than a degraded one, which
            // makes "the event was processed" visible in two independent
            // places.
            "sentinelflow.scoring.client.connect-timeout=250ms",
            "sentinelflow.scoring.client.read-timeout=500ms",
            "sentinelflow.scoring.client.retry-base=1ms",
            "sentinelflow.scoring.client.retry-max-delay=5ms"
        })
class BrokerOutageDrillIT extends AbstractPostgresTest {

    private static final int BEFORE_OUTAGE = 3;

    private static final int DURING_OUTAGE = 6;

    /** Generous: the drill's own timings are seconds, and a cold CI runner is slower than that. */
    private static final Duration TIMEOUT = Duration.ofSeconds(120);

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

    private static final HttpServer SCORING = startScoringStub();

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
            server.createContext("/v1/score", BrokerOutageDrillIT::respond);
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot start the scoring stub", e);
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] body = SCORED_BODY.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
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
    }

    /**
     * Resumes the broker whatever happened.
     *
     * <p>There is one broker in this JVM fork. A drill that fails while it is frozen would take
     * every messaging suite scheduled after it down with it, and the failure they reported would be
     * about this file rather than about themselves.
     */
    @AfterEach
    void resumeBroker() {
        KafkaContainerSupport.resumeBroker();
    }

    @AfterAll
    static void stopStub() {
        KafkaContainerSupport.resumeBroker();
        SCORING.stop(0);
    }

    @Test
    @DisplayName("drill: the broker goes away, the outbox retains, and the backlog drains exactly once")
    void survivesABrokerOutage() {
        // ------------------------------------------------------------------ //
        // Phase 1 - healthy. Establishes that ingestion, the relay, the broker
        // and the consumer are all working, so a stuck row in phase 2 is
        // evidence about the outage rather than about the wiring.
        // ------------------------------------------------------------------ //
        List<UUID> before = postTransactions(BEFORE_OUTAGE);
        awaitPublished(before);
        awaitAssessed(before);

        // ------------------------------------------------------------------ //
        // Phase 2 - the outage. Ingestion is the assertion here: every POST is
        // still accepted, because the API's write path ends at a table and
        // never at the broker.
        // ------------------------------------------------------------------ //
        KafkaContainerSupport.pauseBroker();
        assertThat(KafkaContainerSupport.isBrokerPaused()).isTrue();

        List<UUID> during = postTransactions(DURING_OUTAGE);

        assertThat(pendingCount(during))
                .as("the events are in the table, which is the whole of ADR-0005's claim: they are "
                        + "durable before they are published, so an unreachable broker delays them "
                        + "rather than losing them")
                .isEqualTo(DURING_OUTAGE);

        // The relay is trying and failing. Waited for rather than assumed: the
        // drain is transactional, so an attempt is only visible once it has
        // committed, and asserting immediately would assert on whatever the
        // scheduler happened to have finished.
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(250)).until(() -> attemptedCount(during) >= 1);

        assertThat(lastErrorsRecorded(during))
                .as("a failed attempt records why, so an operator reading the table is not guessing")
                .isGreaterThanOrEqualTo(1);
        assertThat(failedCount(during))
                .as("nothing gave up: the retry budget is sized to ride out a broker restart, and "
                        + "a row that reached FAILED during an outage of seconds would mean it is not")
                .isZero();
        assertThat(gauge("sentinelflow.outbox.pending"))
                .as("the gauge an operator watches during exactly this incident is reporting the backlog")
                .isGreaterThanOrEqualTo((double) DURING_OUTAGE);
        assertThat(gauge("sentinelflow.outbox.oldest.age.seconds"))
                .as("and the age beside it is non-zero, which is what distinguishes a queue that is "
                        + "turning over from one that is stuck")
                .isGreaterThan(0.0d);

        // ------------------------------------------------------------------ //
        // Phase 3 - recovery. Nothing is replayed by hand and nothing is
        // reset: the relay's next poll finds the same rows still due.
        // ------------------------------------------------------------------ //
        KafkaContainerSupport.resumeBroker();

        awaitPublished(during);
        awaitAssessed(during);

        // The whole outbox empties, not only this drill's transaction.created
        // rows: the gauge is over the table, so the risk.assessed events the
        // recovered consumption wrote are in this number too. Waiting for zero
        // therefore says the relay caught up on everything the outage held, in
        // both directions of the pipeline.
        await().atMost(TIMEOUT)
                .pollInterval(Duration.ofMillis(250))
                .until(() -> gauge("sentinelflow.outbox.pending") == 0.0d);

        // ------------------------------------------------------------------ //
        // The ledger. Nothing lost is the count; nothing duplicated is the
        // primary key on processed_events doing its job across a redelivery
        // that an outage makes likely rather than theoretical.
        // ------------------------------------------------------------------ //
        List<UUID> all = new ArrayList<>(before);
        all.addAll(during);

        assertThat(processedEventCount(all))
                .as("one ledger row per event, for every event either phase produced")
                .isEqualTo(all.size());
        assertThat(assessmentCount(all))
                .as("and one assessment per transaction - a redelivered event does not write a second")
                .isEqualTo(all.size());
        assertThat(publishedCount(all)).as("every outbox row reached PUBLISHED").isEqualTo(all.size());
    }

    // ----------------------------------------------------------------------- //
    // Posting, and waiting for the pipeline to catch up
    // ----------------------------------------------------------------------- //

    /**
     * Posts {@code count} transactions, each on its own account, asserting each is accepted.
     *
     * <p>The 202 is an assertion, not a formality. During phase 2 it is <em>the</em> assertion: an
     * API that answered 503 because the broker is down would have the coupling the outbox was
     * introduced to remove.
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
                            Map.of("value", "64.20", "currency", "GBP"),
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

    private void awaitPublished(List<UUID> transactionIds) {
        await().atMost(TIMEOUT)
                .pollInterval(Duration.ofMillis(250))
                .until(() -> publishedCount(transactionIds) == transactionIds.size());
    }

    private void awaitAssessed(List<UUID> transactionIds) {
        await().atMost(TIMEOUT)
                .pollInterval(Duration.ofMillis(250))
                .until(() -> assessmentCount(transactionIds) == transactionIds.size());
    }

    private int pendingCount(List<UUID> transactionIds) {
        return count(
                "SELECT count(*) FROM outbox_events WHERE status = 'PENDING' AND aggregate_id IN (%s)", transactionIds);
    }

    private int publishedCount(List<UUID> transactionIds) {
        return count(
                "SELECT count(*) FROM outbox_events WHERE status = 'PUBLISHED' AND published_at IS NOT NULL "
                        + "AND aggregate_id IN (%s)",
                transactionIds);
    }

    private int failedCount(List<UUID> transactionIds) {
        return count(
                "SELECT count(*) FROM outbox_events WHERE status = 'FAILED' AND aggregate_id IN (%s)", transactionIds);
    }

    private int attemptedCount(List<UUID> transactionIds) {
        return count(
                "SELECT count(*) FROM outbox_events WHERE attempt_count >= 1 AND aggregate_id IN (%s)", transactionIds);
    }

    private int lastErrorsRecorded(List<UUID> transactionIds) {
        return count(
                "SELECT count(*) FROM outbox_events WHERE last_error IS NOT NULL AND aggregate_id IN (%s)",
                transactionIds);
    }

    private int assessmentCount(List<UUID> transactionIds) {
        return count("SELECT count(*) FROM risk_assessments WHERE transaction_id IN (%s)", transactionIds);
    }

    /**
     * How many of these transactions' events the consumer has recorded in its idempotency ledger.
     *
     * <p>Joined through {@code outbox_events} rather than counted from a list of event ids the test
     * kept, because the event id in the ledger is the one the envelope carried and the one the
     * relay stored — the join asserts they are the same identifier, which is the property that makes
     * deduplication work at all.
     */
    private int processedEventCount(List<UUID> transactionIds) {
        return jdbc.queryForObject(
                String.format("""
                        SELECT count(*) FROM processed_events p
                          JOIN outbox_events o ON o.id = p.event_id
                         WHERE p.consumer_name = ? AND o.aggregate_id IN (%s)
                        """, placeholders(transactionIds)),
                Integer.class,
                arguments(TransactionCreatedConsumer.CONSUMER_NAME, transactionIds));
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
        return String.join(", ", Collections.nCopies(ids.size(), "?"));
    }

    private static Object[] arguments(Object first, List<UUID> rest) {
        List<Object> arguments = new ArrayList<>();
        arguments.add(first);
        arguments.addAll(rest);
        return arguments.toArray();
    }

    private double gauge(String name) {
        return meters.find(name).gauge().value();
    }
}
