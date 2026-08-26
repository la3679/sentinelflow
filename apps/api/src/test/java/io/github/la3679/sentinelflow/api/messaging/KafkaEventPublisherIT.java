/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.test.context.TestPropertySource;

import io.github.la3679.sentinelflow.api.persistence.entity.OutboxEvent;
import io.github.la3679.sentinelflow.api.persistence.repository.OutboxEventRepository;
import io.github.la3679.sentinelflow.api.support.AbstractPostgresTest;
import io.github.la3679.sentinelflow.api.support.KafkaContainerSupport;
import io.github.la3679.sentinelflow.api.support.SchemaFixtures;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What actually reaches the broker.
 *
 * <p>Against a real Kafka. Mocking {@code KafkaTemplate} would answer every question here by
 * construction: whether the envelope is an object rather than an escaped string, whether the key is
 * the stored partition key, and whether a publish returns before the broker has acknowledged are all
 * properties of the transport, and a mock has whichever ones it was written to have.
 */
@Import(KafkaContainerSupport.class)
@TestPropertySource(properties = "sentinelflow.outbox.enabled=false")
class KafkaEventPublisherIT extends AbstractPostgresTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private KafkaEventPublisher publisher;

    @Autowired
    private OutboxEventRepository outbox;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE outbox_events");
    }

    private OutboxEvent storedEvent(String partitionKey, String payload) {
        UUID id = jdbc.queryForObject("""
                INSERT INTO outbox_events (
                    aggregate_type, aggregate_id, event_type, schema_version, partition_key,
                    payload, status, correlation_id, trace_id, occurred_at)
                VALUES ('transaction', gen_random_uuid(), 'transaction.created', 1, ?,
                        ?::jsonb, 'PENDING', gen_random_uuid(), ?, now())
                RETURNING id
                """, UUID.class, partitionKey, payload, "4bf92f3577b34da6a3ce929d0e0e4736");
        return outbox.findById(id).orElseThrow();
    }

    private Consumer<String, String> consumerFor(String topic) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                KafkaContainerSupport.bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                false);

        Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
                        config, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    /**
     * Waits for the record this test published, identified by its key.
     *
     * <p>Not "the first record on the topic". The broker is shared across this suite and the topic
     * name is fixed by the contract, so a consumer starting at the earliest offset sees every record
     * any earlier test published. Taking the first one made this assert against another test's
     * event, which is exactly how it failed the first time it ran.
     */
    private ConsumerRecord<String, String> awaitRecordKeyed(Consumer<String, String> consumer, String key) {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                if (key.equals(record.key())) {
                    return record;
                }
            }
        }
        throw new AssertionError("No record keyed " + key + " arrived on the topic within 30 seconds");
    }

    @Test
    @DisplayName("the envelope reaches the topic with the payload as an object, keyed by account")
    void publishesTheEnvelope() {
        String partitionKey = "ACC-" + SchemaFixtures.next6();
        OutboxEvent event = storedEvent(partitionKey, "{\"transactionReference\": \"TXN-000001\"}");

        try (Consumer<String, String> consumer = consumerFor("transaction.created.v1")) {
            publisher.publish(event);
            ConsumerRecord<String, String> record = awaitRecordKeyed(consumer, partitionKey);

            // Keyed by the stored partition key, not something derived at
            // publication time (ADR-0005). A record read out of a dead-letter
            // queue still says how it was keyed.
            assertThat(record.key()).isEqualTo(partitionKey);

            JsonNode envelope = MAPPER.readTree(record.value());
            // eventId is the outbox row's own primary key. Minting a new one
            // per attempt would give a retried event a second identity and
            // defeat consumer deduplication entirely.
            assertThat(envelope.get("eventId").asString())
                    .isEqualTo(event.getId().toString());
            assertThat(envelope.get("eventType").asString()).isEqualTo("transaction.created");
            assertThat(envelope.get("aggregateType").asString()).isEqualTo("transaction");
            assertThat(envelope.get("schemaVersion").asInt()).isEqualTo(1);
            assertThat(envelope.get("producer").asString()).isEqualTo("sentinelflow-api");
            assertThat(envelope.get("traceId").asString()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");

            // An object, not an escaped string. Binding the stored JSON text to
            // a String field would publish "{\"a\":1}" and every consumer would
            // have to parse it a second time.
            assertThat(envelope.get("payload").isObject()).isTrue();
            assertThat(envelope.get("payload").get("transactionReference").asString())
                    .isEqualTo("TXN-000001");
        }
    }

    @Test
    @DisplayName("a missing trace context is published as null, not omitted")
    void traceIdIsNullRatherThanAbsent() {
        String partitionKey = "ACC-" + SchemaFixtures.next6();
        UUID id = jdbc.queryForObject("""
                INSERT INTO outbox_events (
                    aggregate_type, aggregate_id, event_type, schema_version, partition_key,
                    payload, status, correlation_id, occurred_at)
                VALUES ('transaction', gen_random_uuid(), 'transaction.created', 1, ?,
                        '{}'::jsonb, 'PENDING', gen_random_uuid(), now())
                RETURNING id
                """, UUID.class, partitionKey);
        OutboxEvent event = outbox.findById(id).orElseThrow();

        try (Consumer<String, String> consumer = consumerFor("transaction.created.v1")) {
            publisher.publish(event);
            JsonNode envelope =
                    MAPPER.readTree(awaitRecordKeyed(consumer, partitionKey).value());

            // The schema requires traceId present and nullable, so a consumer
            // never distinguishes "no trace context" from "field missing".
            assertThat(envelope.has("traceId")).isTrue();
            assertThat(envelope.get("traceId").isNull()).isTrue();
        }
    }

    @Test
    @DisplayName("an unreachable broker throws rather than reporting a publication that did not happen")
    void unreachableBrokerFails() {
        // The publisher must not return normally when the broker has not
        // acknowledged: the relay marks the row PUBLISHED the instant it
        // returns, so a silent failure here reintroduces exactly the lost-event
        // problem the outbox exists to prevent.
        OutboxEvent event = storedEvent("ACC-" + SchemaFixtures.next6(), "{}");
        KafkaEventPublisher unreachable = new KafkaEventPublisher(UnreachableKafka.template("127.0.0.1:1"), MAPPER);

        assertThatThrownBy(() -> unreachable.publish(event))
                .isInstanceOf(EventPublicationException.class)
                .hasMessageContaining("transaction.created.v1");
    }

    /** A {@code KafkaTemplate} pointed at a port nothing is listening on. */
    private static final class UnreachableKafka {

        static org.springframework.kafka.core.KafkaTemplate<String, String> template(String bootstrap) {
            Map<String, Object> config = Map.of(
                    org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                    bootstrap,
                    org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                    org.apache.kafka.common.serialization.StringSerializer.class,
                    org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                    org.apache.kafka.common.serialization.StringSerializer.class,
                    // Short, so the test asserts the failure path in seconds
                    // rather than waiting out the production delivery timeout.
                    org.apache.kafka.clients.producer.ProducerConfig.MAX_BLOCK_MS_CONFIG,
                    2000,
                    org.apache.kafka.clients.producer.ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
                    3000,
                    org.apache.kafka.clients.producer.ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,
                    1500);

            return new org.springframework.kafka.core.KafkaTemplate<>(
                    new org.springframework.kafka.core.DefaultKafkaProducerFactory<>(config));
        }
    }
}
