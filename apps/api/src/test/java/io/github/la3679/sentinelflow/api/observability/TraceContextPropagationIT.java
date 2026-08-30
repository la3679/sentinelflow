/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Pattern;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import io.github.la3679.sentinelflow.api.support.AbstractPostgresTest;
import io.github.la3679.sentinelflow.api.support.KafkaContainerSupport;
import io.github.la3679.sentinelflow.api.support.SchemaFixtures;

/**
 * One transaction, one trace, across an HTTP hop and an asynchronous one.
 *
 * <h2>What would be wrong without this</h2>
 *
 * The outbox is a deliberate delay (ADR-0005): the request writes a row and returns, and a scheduled
 * relay publishes it later, on another thread, after the request's span has closed. Publishing under
 * whatever context the scheduler happens to be in produces <em>two</em> traces with nothing joining
 * them — a transaction followable through ingestion, followable again from the relay onward, and
 * never followable end to end. That failure is completely invisible: every span is well-formed,
 * every service is healthy, and the only symptom is that the trace an operator opens stops halfway.
 *
 * <p>So this asserts the join. The trace the API records for the HTTP request must be the trace on
 * the record the consumer receives, with the request's span as its parent.
 *
 * <h2>Real tracer, real broker, real database</h2>
 *
 * Tracing is switched on for this context, which means the OpenTelemetry bridge is producing the
 * identifiers rather than a fake. That matters for one assertion in particular: the W3C widths.
 * {@code TraceStampTests} covers the composition against {@code SimpleTracer}, whose identifiers are
 * the wrong length, so the claim that what gets stored satisfies
 * {@code outbox_events_trace_parent_format} can only be made here — and if it were false, every
 * insert would fail in production and pass in that unit test.
 *
 * <p>Export stays off. There is no collector on this network, and a trace is observable here in the
 * two places that matter: the row and the record.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(KafkaContainerSupport.class)
@TestPropertySource(
        properties = {
            // The relay is the component under test: it is what carries the
            // stored context onto the record.
            "sentinelflow.outbox.enabled=true",
            "sentinelflow.outbox.poll-interval=100ms",
            // Off. This asserts what reaches the broker; a consumer would only
            // race the reader below for the same records.
            "sentinelflow.consumer.enabled=false",
            "sentinelflow.observability.kafka.enabled=false",
            // On, with a real tracer behind it. Off in AbstractPostgresTest's
            // world by omission, because nothing there needs spans.
            "management.tracing.sampling.probability=1.0",
            // Nothing is listening for OTLP on this network, and an exporter
            // retrying a connection it cannot make would fill the output with
            // stack traces about a collector this suite never wanted.
            //
            // management.tracing.export.otlp, not management.otlp.tracing:
            // the second is the pre-4.1 spelling, it still appears in the
            // configuration metadata, and it binds nothing. Setting it here
            // would leave the exporter on and this suite would pass anyway,
            // which is how the same mistake stayed invisible in
            // application.yaml until the stack was run.
            "management.tracing.export.otlp.enabled=false"
        })
class TraceContextPropagationIT extends AbstractPostgresTest {

    /** Exactly what {@code outbox_events_trace_parent_format} enforces. */
    private static final Pattern TRACEPARENT = Pattern.compile("^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");

    private static final String TOPIC = "transaction.created.v1";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static KafkaConsumer<String, String> reader;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

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

        if (reader == null) {
            reader = subscribe();
        }
    }

    @AfterAll
    static void closeReader() {
        if (reader != null) {
            reader.close();
            reader = null;
        }
    }

    @Test
    @DisplayName("the trace of the HTTP request is the trace on the record the consumer receives")
    void oneTraceCrossesTheOutbox() {
        String key = "trace-" + SchemaFixtures.next6();

        HttpResponse<String> response = post(key);
        assertThat(response.statusCode()).isEqualTo(202);

        Map<String, Object> row = await().atMost(TIMEOUT)
                .pollInterval(Duration.ofMillis(100))
                .until(() -> outboxRowFor(key), stored -> stored.get("trace_parent") != null);

        String traceId = (String) row.get("trace_id");
        String traceParent = (String) row.get("trace_parent");

        assertThat(traceParent)
                .as("the shape the database enforces, produced by the real tracer rather than by "
                        + "a fake whose identifiers are the wrong width")
                .matches(value -> TRACEPARENT.matcher(value).matches());
        assertThat(traceParent)
                .as("outbox_events_trace_parent_agrees_with_trace_id; the row would not have been "
                        + "written otherwise, so this says why it was accepted")
                .contains(traceId);

        ConsumerRecord<String, String> published = awaitRecordKeyed(accountReference);

        assertThat(headerOf(published, "traceparent"))
                .as("the relay replays the stored context rather than publishing under the "
                        + "scheduler's own, which is what makes this one trace instead of two")
                .isEqualTo(traceParent);
        assertThat(published.value())
                .as("the envelope carries the trace id as well, so a consumer can name the "
                        + "originating request without reading a header")
                .contains(traceId);
    }

    @Test
    @DisplayName("two requests produce two traces, so the join is a join and not a constant")
    void differentRequestsGetDifferentTraces() {
        String first = "trace-" + SchemaFixtures.next6();
        String second = "trace-" + SchemaFixtures.next6();

        assertThat(post(first).statusCode()).isEqualTo(202);
        assertThat(post(second).statusCode()).isEqualTo(202);

        String firstTrace = await().atMost(TIMEOUT)
                .pollInterval(Duration.ofMillis(100))
                .until(() -> outboxRowFor(first), row -> row.get("trace_id") != null)
                .get("trace_id")
                .toString();
        String secondTrace = await().atMost(TIMEOUT)
                .pollInterval(Duration.ofMillis(100))
                .until(() -> outboxRowFor(second), row -> row.get("trace_id") != null)
                .get("trace_id")
                .toString();

        assertThat(firstTrace)
                .as("a stamp that were somehow constant would satisfy every assertion in the test "
                        + "above and be useless")
                .isNotEqualTo(secondTrace);
    }

    // ----------------------------------------------------------------------- //
    // Plumbing
    // ----------------------------------------------------------------------- //

    private Map<String, Object> outboxRowFor(String idempotencyKey) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT o.trace_id, o.trace_parent
                FROM outbox_events o
                JOIN transactions t ON t.id = o.aggregate_id
                WHERE t.idempotency_key = ?
                """, idempotencyKey);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    private ConsumerRecord<String, String> awaitRecordKeyed(String key) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            ConsumerRecords<String, String> polled = reader.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : polled) {
                if (key.equals(record.key())) {
                    return record;
                }
            }
        }
        throw new AssertionError("no record keyed " + key + " arrived on " + TOPIC + " within " + TIMEOUT);
    }

    private static String headerOf(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        assertThat(header).as("no %s header on the record", name).isNotNull();
        return new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static KafkaConsumer<String, String> subscribe() {
        Properties configuration = new Properties();
        configuration.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaContainerSupport.bootstrapServers());
        configuration.put(ConsumerConfig.GROUP_ID_CONFIG, "trace-propagation-it-" + UUID.randomUUID());
        configuration.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        configuration.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        configuration.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configuration.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(configuration);
        consumer.subscribe(List.of(TOPIC));
        // One poll to force the assignment, so the first record published by a
        // test is not missed while the group is still joining.
        consumer.poll(Duration.ofSeconds(5));
        return consumer;
    }

    private HttpResponse<String> post(String idempotencyKey) {
        String body = """
                {
                  "idempotencyKey": "%s",
                  "accountReference": "%s",
                  "merchantReference": "%s",
                  "type": "PURCHASE",
                  "channel": "CARD_NOT_PRESENT",
                  "amount": {"value": "12.00", "currency": "GBP"},
                  "originCountry": "GB",
                  "deviceReference": "DEV-0123456789ab",
                  "occurredAt": "%s"
                }
                """.formatted(
                        idempotencyKey,
                        accountReference,
                        merchantReference,
                        Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/transactions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
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
