/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.github.la3679.sentinelflow.api.domain.DlqFailureClass;
import io.github.la3679.sentinelflow.api.messaging.EventEnvelope;
import io.github.la3679.sentinelflow.api.messaging.payload.TransactionCreatedPayload;
import io.github.la3679.sentinelflow.api.risk.ScoringTransactionCreatedHandler;
import io.github.la3679.sentinelflow.api.support.AbstractPostgresTest;
import io.github.la3679.sentinelflow.api.support.KafkaContainerSupport;
import io.github.la3679.sentinelflow.api.support.SchemaFixtures;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The consumer end to end: real broker, real database, real listener container.
 *
 * <p>Everything worth asserting here is emergent. Whether a duplicate is harmless depends on a
 * database constraint; whether a poison record blocks a partition depends on the container's error
 * handler; whether a dead-letter record is well-formed depends on what a broker actually holds
 * afterwards. Each of those is decided by a component this test does not own, and a mock of any of
 * them would answer the question by construction.
 *
 * <p><strong>The retry budget is compressed to milliseconds.</strong> The shipped schedule takes
 * roughly half a minute to exhaust, which is right in production and would make this suite unusable.
 * {@code FullJitterBackOffTests} covers the schedule's shape; this covers what happens at the end of
 * it, so the two together say more than one slow test would.
 */
@Import({KafkaContainerSupport.class, TransactionCreatedConsumerIT.HandlerConfiguration.class})
@TestPropertySource(
        properties = {
            // Nothing here goes through the outbox: events are published
            // directly, so a relay thread would only add a second source of
            // records the assertions did not expect.
            "sentinelflow.outbox.enabled=false",
            // Back on: the base class turns the listener off because most
            // contexts have no broker, and this one is the exception.
            "sentinelflow.consumer.enabled=true",
            "sentinelflow.consumer.retry-base=10ms",
            "sentinelflow.consumer.retry-max-delay=40ms",
            "sentinelflow.consumer.max-attempts=3"
        })
class TransactionCreatedConsumerIT extends AbstractPostgresTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String DLQ_TOPIC = "transaction.processing.dlq.v1";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /**
     * One dead-letter reader for the whole class, subscribed before the first event is published.
     *
     * <p>A consumer created after the fact would have to reason about where its offsets landed. This
     * one is positioned once and read forward, so each test sees the records its own actions caused.
     */
    private static org.apache.kafka.clients.consumer.Consumer<String, String> dlqReader;

    /**
     * The real scoring handler, replaced by a no-op for this suite.
     *
     * <p>Not because it is inconvenient. This suite's subject is <em>delivery</em> — deduplication,
     * retry classification, dead-lettering — and the consumer dispatches to every registered handler,
     * so leaving the scoring one in would make every assertion here depend on what the risk workflow
     * did as well. That is exactly the coupling {@code TransactionCreatedHandler} exists to prevent,
     * and it showed up the moment the first implementation was registered: this class asserted that a
     * successfully handled transaction stays {@code PENDING}, which stopped being true because
     * scoring correctly moves it to {@code ASSESSED}.
     *
     * <p>The scoring handler's own behaviour is asserted by {@code RiskAssessmentWorkflowIT}, against
     * the same broker and the same database.
     */
    @MockitoBean
    private ScoringTransactionCreatedHandler scoringHandler;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private ControllableHandler handler;

    @Autowired
    private JdbcTemplate jdbc;

    private SchemaFixtures fixtures;

    @BeforeEach
    void reset() {
        fixtures = new SchemaFixtures(jdbc);
        handler.reset();
        if (dlqReader == null) {
            dlqReader = subscribe(DLQ_TOPIC);
        }
    }

    @AfterAll
    static void closeReader() {
        if (dlqReader != null) {
            dlqReader.close();
            dlqReader = null;
        }
    }

    // ---------------------------------------------------------------- delivery

    @Test
    @DisplayName("an accepted transaction reaches every handler, once, with its payload intact")
    void deliversToHandlers() {
        UUID transactionId = fixtures.insertTransaction();
        UUID eventId = publish(envelope(eventId(), transactionId));

        // The ledger row, not the delivery counter. The handler increments that
        // counter on entry, so it is true before the surrounding transaction
        // has committed - waiting on it and then reading the ledger is a race
        // this suite lost on a slower runner once the listener started
        // creating an observation per record.
        await().atMost(TIMEOUT).until(() -> ledgerRows(eventId) == 1);
        assertThat(handler.deliveries()).isEqualTo(1);

        TransactionCreatedPayload delivered = handler.lastPayload();
        assertThat(delivered.transactionId()).isEqualTo(transactionId);
        // The amount survived as a decimal string rather than becoming a double
        // somewhere in the round trip (ADR-0007).
        assertThat(delivered.amount().value()).isEqualTo("42.5000");
    }

    @Test
    @DisplayName("a redelivered event is handled once, because at-least-once is ordinary traffic")
    void deduplicatesRedeliveries() {
        UUID transactionId = fixtures.insertTransaction();
        UUID eventId = eventId();

        publish(envelope(eventId, transactionId));
        publish(envelope(eventId, transactionId));

        await().atMost(TIMEOUT).until(() -> handler.deliveries() >= 1);
        // The second record is consumed either way; what must not happen is a
        // second effect. Held long enough that "not yet" cannot pass for "never".
        await().pollDelay(Duration.ofSeconds(2)).atMost(TIMEOUT).until(() -> handler.deliveries() == 1);

        // Committed by now: the poll delay above is two seconds, which is well
        // past the window the counter races the transaction in.
        assertThat(ledgerRows(eventId)).isEqualTo(1);
    }

    // ----------------------------------------------------------------- retries

    @Test
    @DisplayName("a retryable failure is retried, and a handler that recovers is not dead-lettered")
    void retriesUntilTheHandlerRecovers() {
        UUID transactionId = fixtures.insertTransaction();
        // Fails twice, succeeds on the third delivery - inside a budget of three.
        handler.failNext(2, attempt -> {
            throw new IllegalStateException("scoring unavailable, attempt " + attempt);
        });

        UUID eventId = publish(envelope(eventId(), transactionId));

        await().atMost(TIMEOUT).until(() -> handler.successes() == 1);
        assertThat(handler.deliveries()).isEqualTo(3);
        assertThat(ledgerRows(eventId)).isEqualTo(1);
        assertThat(processingStatusOf(transactionId)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("a retryable failure that never clears is dead-lettered as RETRY_EXHAUSTED")
    void deadLettersWhenTheBudgetRunsOut() {
        UUID transactionId = fixtures.insertTransaction();
        handler.failAlways(attempt -> {
            throw new IllegalStateException("scoring is still down on attempt " + attempt);
        });

        UUID eventId = publish(envelope(eventId(), transactionId));

        JsonNode record = awaitDeadLetter(eventId);
        assertThat(record.get("failureClass").asString()).isEqualTo(DlqFailureClass.RETRY_EXHAUSTED.name());
        assertThat(record.get("attemptCount").asInt()).isEqualTo(3);
        assertThat(record.get("consumer").asString()).isEqualTo(TransactionCreatedConsumer.CONSUMER_NAME);
        assertThat(record.get("sourceTopic").asString()).isEqualTo(TransactionCreatedConsumer.TOPIC);
        assertThat(record.get("exceptionType").asString()).isEqualTo("java.lang.IllegalStateException");

        // The effect never succeeded, so nothing may claim it did.
        assertThat(ledgerRows(eventId)).isZero();
        // And the transaction is no longer waiting for an assessment that is not
        // coming.
        await().atMost(TIMEOUT).until(() -> "FAILED".equals(processingStatusOf(transactionId)));
    }

    @Test
    @DisplayName("a non-retryable failure is dead-lettered after one attempt, not after the budget")
    void doesNotRetryWhatCannotSucceed() {
        UUID transactionId = fixtures.insertTransaction();
        handler.failAlways(attempt -> {
            throw new NonRetryableEventException(
                    DlqFailureClass.NON_RETRYABLE_ERROR, "the merchant reference does not exist");
        });

        UUID eventId = publish(envelope(eventId(), transactionId));

        JsonNode record = awaitDeadLetter(eventId);
        assertThat(record.get("failureClass").asString()).isEqualTo(DlqFailureClass.NON_RETRYABLE_ERROR.name());
        // One attempt. Retrying this would fail identically twice more while the
        // whole partition waited behind it.
        assertThat(record.get("attemptCount").asInt()).isEqualTo(1);
        assertThat(handler.deliveries()).isEqualTo(1);
    }

    // ----------------------------------------------------------- poison events

    @Test
    @DisplayName("an unsupported schema version is dead-lettered without ever reaching a handler")
    void rejectsUnsupportedSchemaVersions() {
        UUID eventId = eventId();
        ObjectNode envelope = envelope(eventId, fixtures.insertTransaction());
        envelope.put("schemaVersion", 99);
        publish(envelope);

        JsonNode record = awaitDeadLetter(eventId);
        assertThat(record.get("failureClass").asString()).isEqualTo(DlqFailureClass.SCHEMA_VALIDATION_FAILED.name());
        assertThat(handler.deliveries()).isZero();
        // The envelope is carried through unmodified, so reprocessing replays
        // what failed rather than a reconstruction of it.
        assertThat(record.get("originalEvent").get("schemaVersion").asInt()).isEqualTo(99);
    }

    @Test
    @DisplayName("an event type this topic should not carry is dead-lettered rather than skipped")
    void rejectsUnexpectedEventTypes() {
        UUID eventId = eventId();
        ObjectNode envelope = envelope(eventId, fixtures.insertTransaction());
        envelope.put("eventType", "alert.created");
        publish(envelope);

        assertThat(awaitDeadLetter(eventId).get("failureClass").asString())
                .isEqualTo(DlqFailureClass.UNKNOWN_EVENT_TYPE.name());
    }

    @Test
    @DisplayName("a payload of the wrong shape is dead-lettered, and the envelope survives to say which")
    void rejectsMalformedPayloads() {
        UUID eventId = eventId();
        ObjectNode envelope = envelope(eventId, fixtures.insertTransaction());
        envelope.put("payload", "not an object");
        publish(envelope);

        JsonNode record = awaitDeadLetter(eventId);
        assertThat(record.get("failureClass").asString()).isEqualTo(DlqFailureClass.SCHEMA_VALIDATION_FAILED.name());
        assertThat(record.get("sanitisedMessage").asString()).contains("TransactionCreatedPayload");
    }

    @Test
    @DisplayName("a message that is not an envelope does not block the partition behind it")
    void survivesAnUnparseableMessage() {
        UUID transactionId = fixtures.insertTransaction();

        // Nothing legitimate can be written to the DLQ for this - the schema
        // requires a valid envelope, and copying the bytes would put unsanitised
        // content on an operational topic. It is logged with its coordinates and
        // the offset is committed; what must not happen is the partition
        // stopping.
        kafka.send(TransactionCreatedConsumer.TOPIC, "ACC-poison", "{ this is not json");
        UUID eventId = publish(envelope(eventId(), transactionId));

        // The durable outcome rather than the counter, for the reason given in
        // deliversToHandlers: the counter is true before the commit is.
        await().atMost(TIMEOUT).until(() -> ledgerRows(eventId) == 1);
        assertThat(handler.deliveries()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private static UUID eventId() {
        return UUID.randomUUID();
    }

    /** A complete, valid envelope around a payload for a transaction that exists. */
    private ObjectNode envelope(UUID eventId, UUID transactionId) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT t.transaction_reference, t.idempotency_key, t.origin_country, t.device_reference,
                       t.occurred_at, a.id AS account_id, a.account_reference,
                       m.id AS merchant_id, m.merchant_reference, m.category_code
                  FROM transactions t
                  JOIN accounts a ON a.id = t.account_id
                  JOIN merchants m ON m.id = t.merchant_id
                 WHERE t.id = ?
                """, transactionId);

        var payload = MAPPER.createObjectNode();
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
        payload.putNull("deviceReference");
        payload.put("occurredAt", Instant.now().toString());
        payload.put("ingestionSource", "API");
        payload.put("idempotencyKey", (String) row.get("idempotency_key"));

        var envelope = MAPPER.createObjectNode();
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
        return envelope;
    }

    /** Publishes an envelope keyed by its account, as the relay would, and returns its event id. */
    private UUID publish(JsonNode envelope) {
        String key = "ACC-" + envelope.get("aggregateId").asString();
        kafka.send(TransactionCreatedConsumer.TOPIC, key, MAPPER.writeValueAsString(envelope));
        return UUID.fromString(envelope.get("eventId").asString());
    }

    /** Reads forward on the dead-letter topic until a record for this event appears. */
    private JsonNode awaitDeadLetter(UUID eventId) {
        AtomicReference<JsonNode> found = new AtomicReference<>();
        await().atMost(TIMEOUT).until(() -> {
            ConsumerRecords<String, String> polled = dlqReader.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : polled) {
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

    private long ledgerRows(UUID eventId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM processed_events WHERE consumer_name = ? AND event_id = ?",
                Long.class,
                TransactionCreatedConsumer.CONSUMER_NAME,
                eventId);
        return count == null ? 0 : count;
    }

    private String processingStatusOf(UUID transactionId) {
        return jdbc.queryForObject(
                "SELECT processing_status FROM transactions WHERE id = ?", String.class, transactionId);
    }

    private static org.apache.kafka.clients.consumer.Consumer<String, String> subscribe(String topic) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                KafkaContainerSupport.bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                "dlq-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                false);

        var consumer = new DefaultKafkaConsumerFactory<>(config, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
        consumer.subscribe(List.of(topic));
        // Forces the assignment now rather than on the first meaningful poll, so
        // a record published immediately after this returns is not missed.
        consumer.poll(Duration.ofSeconds(5));
        return consumer;
    }

    /**
     * Registers the handler the test drives.
     *
     * <p>Separate from the handler itself on purpose. A {@code @TestConfiguration} is a bean, so a
     * configuration class that also implemented {@link TransactionCreatedHandler} would be injected
     * into the consumer's list twice — once as itself and once as what its {@code @Bean} method
     * returned — and every delivery would be counted twice.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class HandlerConfiguration {

        @Bean
        ControllableHandler controllableTransactionCreatedHandler() {
            return new ControllableHandler();
        }
    }

    /**
     * A handler the test drives.
     *
     * <p>Registered as an ordinary bean, so the consumer discovers it exactly as Phase 4's scoring
     * handler will — through the injected list, with no test-only branch in production code.
     */
    static class ControllableHandler implements TransactionCreatedHandler {

        private final AtomicInteger deliveries = new AtomicInteger();
        private final AtomicInteger successes = new AtomicInteger();
        private final AtomicReference<TransactionCreatedPayload> lastPayload = new AtomicReference<>();
        private final List<Consumer<Integer>> pendingFailures = new ArrayList<>();
        private volatile Consumer<Integer> alwaysFailWith;

        @Override
        public void handle(EventEnvelope envelope, TransactionCreatedPayload payload) {
            int attempt = deliveries.incrementAndGet();
            lastPayload.set(payload);

            if (alwaysFailWith != null) {
                alwaysFailWith.accept(attempt);
            }
            synchronized (pendingFailures) {
                if (!pendingFailures.isEmpty()) {
                    pendingFailures.removeFirst().accept(attempt);
                }
            }
            successes.incrementAndGet();
        }

        void reset() {
            deliveries.set(0);
            successes.set(0);
            lastPayload.set(null);
            alwaysFailWith = null;
            synchronized (pendingFailures) {
                pendingFailures.clear();
            }
        }

        void failNext(int times, Consumer<Integer> failure) {
            synchronized (pendingFailures) {
                for (int i = 0; i < times; i++) {
                    pendingFailures.add(failure);
                }
            }
        }

        void failAlways(Consumer<Integer> failure) {
            alwaysFailWith = failure;
        }

        int deliveries() {
            return deliveries.get();
        }

        int successes() {
            return successes.get();
        }

        TransactionCreatedPayload lastPayload() {
            return lastPayload.get();
        }
    }
}
