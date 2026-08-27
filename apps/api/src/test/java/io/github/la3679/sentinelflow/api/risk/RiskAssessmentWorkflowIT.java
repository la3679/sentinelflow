/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.github.la3679.sentinelflow.api.domain.DlqFailureClass;
import io.github.la3679.sentinelflow.api.messaging.EventEnvelope;
import io.github.la3679.sentinelflow.api.support.AbstractPostgresTest;
import io.github.la3679.sentinelflow.api.support.KafkaContainerSupport;
import io.github.la3679.sentinelflow.api.support.SchemaFixtures;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The assessment workflow end to end: real broker, real database, real listener, real ruleset, and a
 * scoring service that can be made to answer, to fail, or to refuse.
 *
 * <p>Everything asserted here is emergent, which is why it is an integration test and not a suite of
 * stubs. Whether an assessment and its outbox row commit together depends on a transaction boundary
 * two classes away; whether a redelivered event writes a second assessment depends on a unique
 * constraint and a ledger row; whether a rejected request stalls a partition depends on how the
 * container's error handler classifies what a handler threw. Each of those is decided by something
 * this test does not own, and a mock of any of them would answer the question by construction.
 *
 * <p><strong>The scoring service is a stub HTTP server, not a mocked client.</strong> The three
 * outcomes ADR-0008 §2 distinguishes are transport-level facts — an answer, no answer inside the
 * budget, and a refusal — and a mocked client would assert that the code calls itself while the
 * shipped timeouts sat on nothing. {@code com.sun.net.httpserver} is in the JDK, so the stub costs no
 * dependency.
 *
 * <p><strong>Two budgets are compressed to milliseconds.</strong> The consumer's retry schedule takes
 * roughly half a minute to exhaust and the scoring client's takes a further second per record; both
 * are right in production and would make this suite one nobody runs. Their shapes are covered by
 * {@code FullJitterBackOffTests} and {@code ScoringClientTests}; what this covers is what happens at
 * the end of them.
 */
@Import(KafkaContainerSupport.class)
@TestPropertySource(
        properties = {
            // Events are published directly, so a relay thread would only add a
            // second source of records the assertions did not expect. The outbox
            // rows this workflow *writes* are read from the table rather than
            // from the topic, which is what makes "written in the same commit"
            // the thing being asserted.
            "sentinelflow.outbox.enabled=false",
            "sentinelflow.consumer.enabled=true",
            "sentinelflow.consumer.retry-base=10ms",
            "sentinelflow.consumer.retry-max-delay=40ms",
            "sentinelflow.consumer.max-attempts=3",
            "sentinelflow.scoring.client.connect-timeout=250ms",
            "sentinelflow.scoring.client.read-timeout=500ms",
            "sentinelflow.scoring.client.retry-base=1ms",
            "sentinelflow.scoring.client.retry-max-delay=5ms",
            // The breaker is off for practical purposes: five consecutive
            // failures inside one suite would otherwise carry over from a
            // degradation test into the next test's scored path and make the
            // order of the methods matter.
            "sentinelflow.scoring.client.circuit-breaker-failure-threshold=1000"
        })
class RiskAssessmentWorkflowIT extends AbstractPostgresTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String DLQ_TOPIC = "transaction.processing.dlq.v1";
    private static final String TOPIC = "transaction.created.v1";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final String SCORED_BODY = """
            {
              "modelVersion": "1.0.0",
              "featureVersion": "1.0.0",
              "modelScore": 92.5,
              "reasons": [
                {"code": "VELOCITY_5M_HIGH", "contribution": 0.4213},
                {"code": "HISTORY_SIZE_LOW", "contribution": -1.2}
              ],
              "inferenceDurationMs": 4.269,
              "warnings": []
            }
            """;

    /**
     * One stub for the whole class, started before the context so its port can be bound into
     * {@code sentinelflow.scoring.client.base-url}.
     *
     * <p>Static because {@link DynamicPropertySource} runs once per context, and a per-test server
     * would have a different port than the one the {@code RestClient} was built with.
     */
    private static final HttpServer SCORING = startScoringStub();

    private static final AtomicInteger STATUS = new AtomicInteger(200);
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final AtomicReference<String> LAST_BODY = new AtomicReference<>();

    private static org.apache.kafka.clients.consumer.Consumer<String, String> dlqReader;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private JdbcTemplate jdbc;

    private SchemaFixtures fixtures;

    @DynamicPropertySource
    static void scoringBaseUrl(DynamicPropertyRegistry registry) {
        registry.add(
                "sentinelflow.scoring.client.base-url",
                () -> "http://127.0.0.1:" + SCORING.getAddress().getPort());
    }

    private static HttpServer startScoringStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/score", RiskAssessmentWorkflowIT::respond);
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot start the scoring stub", e);
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        REQUESTS.incrementAndGet();
        LAST_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

        int status = STATUS.get();
        byte[] body = (status == 200 ? SCORED_BODY : "{\"title\":\"Unprocessable\"}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @BeforeEach
    void reset() {
        fixtures = new SchemaFixtures(jdbc);
        STATUS.set(200);
        REQUESTS.set(0);
        if (dlqReader == null) {
            dlqReader = subscribe(DLQ_TOPIC);
        }
    }

    @AfterEach
    void resumeScoring() {
        STATUS.set(200);
    }

    @AfterAll
    static void tearDown() {
        if (dlqReader != null) {
            dlqReader.close();
            dlqReader = null;
        }
        SCORING.stop(0);
    }

    // ----------------------------------------------------------------------- //
    // Scoring answered
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("a scored transaction is assessed, banded, and marked ASSESSED")
    void writesAScoredAssessment() {
        UUID transactionId = fixtures.insertTransaction();

        publish(transactionId);

        Map<String, Object> assessment = awaitAssessment(transactionId);
        assertThat(assessment.get("degraded")).isEqualTo(false);
        assertThat((java.math.BigDecimal) assessment.get("model_score")).isEqualByComparingTo("92.50");
        assertThat((java.math.BigDecimal) assessment.get("final_score"))
                .as("0.6 x 92.5 + 0.4 x 0 = 55.5, and the rule floor of 0 does not raise it")
                .isEqualByComparingTo("55.50");
        assertThat(assessment.get("risk_band")).isEqualTo("MEDIUM");
        assertThat(assessment.get("model_version")).isEqualTo("1.0.0");
        assertThat(assessment.get("feature_version")).isEqualTo("1.0.0");
        assertThat(assessment.get("ruleset_version")).isEqualTo("1.0.0");
        // 1.1.0 since the alerting rule joined the policy object. The version
        // moves when what it describes changes, which is the whole reason an
        // assessment records it.
        assertThat(assessment.get("policy_version")).isEqualTo("1.1.0");
        assertThat(assessment.get("alert_raised"))
                .as("alert creation is Phase 5; true here would be a claim with nothing behind it")
                .isEqualTo(false);

        assertThat(processingStatusOf(transactionId))
                .as("a console showing this as PENDING for ever would be lying about it")
                .isEqualTo("ASSESSED");
    }

    @Test
    @DisplayName("the request carries the transaction and its account context, and nothing else")
    void sendsTheAssembledRequest() {
        UUID transactionId = fixtures.insertTransaction();

        publish(transactionId);
        awaitAssessment(transactionId);

        JsonNode request = MAPPER.readTree(LAST_BODY.get());
        List<String> fields = new ArrayList<>();
        request.propertyNames().forEach(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("transaction", "accountContext");
        assertThat(request.get("transaction").get("transactionId").asString()).isEqualTo(transactionId.toString());
        assertThat(request.get("accountContext").get("contextVersion").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("the reasons carry the model's, sourced and explained")
    void carriesTheModelsReasons() {
        UUID transactionId = fixtures.insertTransaction();

        publish(transactionId);
        JsonNode reasons = json(awaitAssessment(transactionId).get("reason_codes"));

        // The rules fire nothing on a lone transaction with no history, so
        // everything here is the model's - ordered by descending absolute
        // contribution, which puts -1.2 ahead of +0.4213.
        assertThat(reasons.size()).isEqualTo(2);
        assertThat(reasons.get(0).get("code").asString()).isEqualTo("HISTORY_SIZE_LOW");
        assertThat(reasons.get(0).get("source").asString()).isEqualTo("MODEL");
        assertThat(reasons.get(0).get("description").asString()).contains("-1.2000");
        assertThat(reasons.get(1).get("code").asString()).isEqualTo("VELOCITY_5M_HIGH");
    }

    @Test
    @DisplayName("the assessment and its risk.assessed event are written in one commit")
    void writesTheOutboxRowBesideTheAssessment() {
        UUID transactionId = fixtures.insertTransaction();

        publish(transactionId);
        Map<String, Object> assessment = awaitAssessment(transactionId);

        Map<String, Object> event = jdbc.queryForMap(
                "SELECT * FROM outbox_events WHERE event_type = 'risk.assessed' AND aggregate_id = ?",
                assessment.get("id"));

        assertThat(event.get("aggregate_type")).isEqualTo("assessment");
        assertThat(event.get("status")).isEqualTo("PENDING");
        assertThat(event.get("partition_key"))
                .as("keyed by the account, exactly as transaction.created.v1 is, so an account's "
                        + "assessments cannot arrive in an order its transactions never happened in")
                .isEqualTo(accountReferenceOf(transactionId));

        JsonNode payload = json(event.get("payload"));
        assertThat(payload.get("assessmentId").asString())
                .isEqualTo(assessment.get("id").toString());
        assertThat(payload.get("transactionId").asString()).isEqualTo(transactionId.toString());
        assertThat(payload.get("modelScore").asDouble()).isEqualTo(92.5);
        assertThat(payload.get("degraded").asBoolean()).isFalse();
    }

    // ----------------------------------------------------------------------- //
    // Scoring did not answer
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("an unreachable scoring service degrades the assessment rather than losing it")
    void degradesWhenScoringIsUnavailable() {
        STATUS.set(503);
        UUID transactionId = fixtures.insertTransaction();

        publish(transactionId);

        Map<String, Object> assessment = awaitAssessment(transactionId);
        assertThat(assessment.get("degraded")).isEqualTo(true);
        assertThat(assessment.get("model_score"))
                .as("a zero would be a claim about this transaction, and no such claim was made")
                .isNull();
        assertThat(assessment.get("model_version")).isNull();
        assertThat(assessment.get("feature_version")).isNull();
        assertThat(assessment.get("scoring_latency_ms")).isEqualTo(0);
        assertThat(assessment.get("ruleset_version"))
                .as("the rules are the only half this assessment is made of, so this is the " + "version it most needs")
                .isEqualTo("1.0.0");

        assertThat(processingStatusOf(transactionId))
                .as("scoring being down delays or degrades an assessment; it never loses one")
                .isEqualTo("ASSESSED");
    }

    @Test
    @DisplayName("a degraded assessment says so rather than looking like an ordinary one")
    void aDegradedAssessmentExplainsItself() {
        STATUS.set(503);
        UUID transactionId = fixtures.insertTransaction();

        publish(transactionId);
        JsonNode reasons = json(awaitAssessment(transactionId).get("reason_codes"));

        // A lone transaction trips no rule, and the model did not answer, so
        // there is genuinely nothing to say - and the column requires that
        // something be said anyway.
        assertThat(reasons.size()).isEqualTo(1);
        assertThat(reasons.get(0).get("code").asString()).isEqualTo("NO_INDICATORS");
        assertThat(reasons.get(0).get("source").asString()).isEqualTo("RULE");
    }

    // ----------------------------------------------------------------------- //
    // Scoring refused
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("a rejected request is dead-lettered rather than degraded, and writes no assessment")
    void deadLettersWhenScoringRejectsTheRequest() {
        STATUS.set(422);
        UUID transactionId = fixtures.insertTransaction();

        UUID eventId = publish(transactionId);

        JsonNode record = awaitDeadLetter(eventId);
        assertThat(record.get("failureClass").asString())
                .as("a contract mismatch between two services in one repository is a defect to fix, "
                        + "not a condition to absorb as a degraded assessment")
                .isEqualTo(DlqFailureClass.NON_RETRYABLE_ERROR.name());
        assertThat(record.get("attemptCount").asInt())
                .as("never retried: it will not become valid, and retrying costs the whole "
                        + "partition queued behind it")
                .isEqualTo(1);

        assertThat(assessmentCount(transactionId)).isZero();
        assertThat(processingStatusOf(transactionId))
                .as("the dead-letter recoverer marks it, because the assessment is not late - it is " + "not coming")
                .isEqualTo("FAILED");
    }

    // ----------------------------------------------------------------------- //
    // Delivered twice
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("a redelivered event does not produce a second assessment")
    void isIdempotentUnderRedelivery() {
        UUID transactionId = fixtures.insertTransaction();
        UUID eventId = UUID.randomUUID();

        publish(transactionId, eventId);
        awaitAssessment(transactionId);
        publish(transactionId, eventId);

        // Held long enough that "not yet" cannot pass for "never". At-least-once
        // delivery makes a duplicate ordinary traffic, and a second assessment
        // would be a second decision about one transaction with nothing to
        // choose between them.
        await().pollDelay(Duration.ofSeconds(2)).atMost(TIMEOUT).until(() -> assessmentCount(transactionId) == 1);
        assertThat(outboxRowCount(transactionId)).isEqualTo(1);
    }

    // ----------------------------------------------------------------------- //
    // Fixtures and reads
    // ----------------------------------------------------------------------- //

    /** A complete, valid envelope around a payload for a transaction that exists, published as the relay would. */
    private UUID publish(UUID transactionId) {
        return publish(transactionId, UUID.randomUUID());
    }

    private UUID publish(UUID transactionId, UUID eventId) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT t.transaction_reference, t.idempotency_key, t.origin_country, t.occurred_at,
                       a.id AS account_id, a.account_reference,
                       m.id AS merchant_id, m.merchant_reference, m.category_code
                  FROM transactions t
                  JOIN accounts a ON a.id = t.account_id
                  JOIN merchants m ON m.id = t.merchant_id
                 WHERE t.id = ?
                """, transactionId);

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("transactionId", transactionId.toString());
        payload.put("transactionReference", (String) row.get("transaction_reference"));
        payload.put("accountId", row.get("account_id").toString());
        payload.put("accountReference", (String) row.get("account_reference"));
        payload.put("merchantId", row.get("merchant_id").toString());
        payload.put("merchantReference", (String) row.get("merchant_reference"));
        payload.put("merchantCategoryCode", (String) row.get("category_code"));
        payload.put("type", "PURCHASE");
        payload.put("channel", "CARD_NOT_PRESENT");
        payload.putObject("amount").put("value", "42.5000").put("currency", "GBP");
        payload.put("originCountry", (String) row.get("origin_country"));
        // Required and nullable: null rather than absent, so a consumer never
        // distinguishes "this channel has no device" from "the producer forgot".
        payload.putNull("deviceReference");
        // timestamptz comes back from JdbcTemplate as a java.sql.Timestamp, not
        // an Instant. Carrying the stored value rather than Instant.now() so the
        // payload describes the transaction the assembler will window against.
        payload.put(
                "occurredAt",
                ((java.sql.Timestamp) row.get("occurred_at")).toInstant().toString());
        payload.put("ingestionSource", "API");
        payload.put("idempotencyKey", (String) row.get("idempotency_key"));

        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", "transaction.created");
        envelope.put("schemaVersion", 1);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("producer", EventEnvelope.PRODUCER);
        envelope.put("correlationId", UUID.randomUUID().toString());
        envelope.putNull("traceId");
        envelope.put("aggregateType", "transaction");
        envelope.put("aggregateId", transactionId.toString());
        envelope.set("payload", payload);

        kafka.send(TOPIC, (String) row.get("account_reference"), MAPPER.writeValueAsString(envelope));
        return eventId;
    }

    /**
     * A {@code jsonb} column as a tree.
     *
     * <p>The driver hands one back as a {@code PGobject} rather than a {@code String}, so the obvious
     * cast compiles and fails at runtime. Its {@code toString} is the JSON text.
     */
    private static JsonNode json(Object column) {
        return MAPPER.readTree(String.valueOf(column));
    }

    private Map<String, Object> awaitAssessment(UUID transactionId) {
        await().atMost(TIMEOUT).until(() -> assessmentCount(transactionId) == 1);
        return jdbc.queryForMap("SELECT * FROM risk_assessments WHERE transaction_id = ?", transactionId);
    }

    private int assessmentCount(UUID transactionId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM risk_assessments WHERE transaction_id = ?", Integer.class, transactionId);
    }

    private int outboxRowCount(UUID transactionId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM outbox_events o
                  JOIN risk_assessments r ON r.id = o.aggregate_id
                 WHERE o.event_type = 'risk.assessed' AND r.transaction_id = ?
                """, Integer.class, transactionId);
    }

    private String processingStatusOf(UUID transactionId) {
        return jdbc.queryForObject(
                "SELECT processing_status FROM transactions WHERE id = ?", String.class, transactionId);
    }

    private String accountReferenceOf(UUID transactionId) {
        return jdbc.queryForObject("""
                SELECT a.account_reference FROM accounts a
                  JOIN transactions t ON t.account_id = a.id
                 WHERE t.id = ?
                """, String.class, transactionId);
    }

    private JsonNode awaitDeadLetter(UUID eventId) {
        AtomicReference<JsonNode> found = new AtomicReference<>();
        await().atMost(TIMEOUT).until(() -> {
            ConsumerRecords<String, String> records = dlqReader.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                JsonNode parsed = MAPPER.readTree(record.value());
                if (eventId.toString()
                        .equals(parsed.get("originalEvent").get("eventId").asString())) {
                    found.set(parsed);
                    return true;
                }
            }
            return false;
        });
        return found.get();
    }

    private org.apache.kafka.clients.consumer.Consumer<String, String> subscribe(String topic) {
        Map<String, Object> configuration = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                KafkaContainerSupport.bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                "risk-assessment-workflow-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest");
        org.apache.kafka.clients.consumer.Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
                        configuration, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
        consumer.subscribe(List.of(topic));
        consumer.poll(Duration.ofMillis(500));
        return consumer;
    }
}
