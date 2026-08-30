/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.messaging;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import io.github.la3679.sentinelflow.api.persistence.entity.OutboxEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Publishes a claimed outbox row to Kafka, and does not return until the broker says so.
 *
 * <p><strong>Synchronous on purpose.</strong> {@link KafkaTemplate#send} returns a future; the relay
 * marks a row {@code PUBLISHED} as soon as this method returns, inside the transaction that claimed
 * it. Returning on the future rather than on the acknowledgement would let the relay record a
 * publication that had not happened — which is precisely the failure the outbox exists to prevent,
 * reintroduced at the last step.
 *
 * <p><strong>The send is bounded.</strong> Waiting forever would hold the claim's row locks
 * indefinitely, and those locks are what stop a second relay instance from touching the same rows.
 * A slow broker would become a stuck database rather than a slow one.
 *
 * <p><strong>The key is the stored partition key</strong>, not something derived here. The relay
 * therefore cannot change partitioning by changing a getter, and a record pulled out of a
 * dead-letter queue still says how it was keyed (ADR-0005).
 */
@Component
public class KafkaEventPublisher implements EventPublisher {

    /**
     * Longer than the producer's own delivery timeout would need to be, so an expiry here means the
     * broker is genuinely unreachable rather than merely slow.
     */
    private static final long SEND_TIMEOUT_SECONDS = 30;

    /**
     * The W3C trace-context header, spelled the way the specification spells it.
     *
     * <p>Lower case and not {@code X-} prefixed. Spring Kafka's listener-side propagation looks for
     * exactly this name, so a capitalised variant would be written, carried, and silently ignored —
     * a trace that stops at the broker for a reason nothing reports.
     */
    private static final String TRACEPARENT_HEADER = "traceparent";

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper objectMapper) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(OutboxEvent event) {
        String topic = EventTopics.topicFor(event.getEventType());
        String body = serialise(event);

        try {
            kafka.send(record(topic, event, body)).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // Restore the flag rather than swallowing it: something is shutting
            // this thread down, and the relay's caller needs to see that.
            Thread.currentThread().interrupt();
            throw new EventPublicationException("Interrupted while publishing to " + topic, e);
        } catch (ExecutionException | TimeoutException e) {
            // Retryable. The relay schedules another attempt with backoff; it
            // does not mark the row published, and it does not lose the event.
            throw new EventPublicationException("Broker did not acknowledge publication to " + topic, e);
        } catch (RuntimeException e) {
            // send() does not always return a failed future. When the producer
            // cannot even fetch metadata it throws synchronously - a
            // KafkaException wrapping a timeout on max.block.ms - and that
            // escapes the two checked cases above. The relay would still handle
            // it, because it catches RuntimeException, but this port's contract
            // says EventPublicationException and a contract that holds only
            // most of the time is not one. Found by pointing a publisher at a
            // closed port.
            throw new EventPublicationException("Could not publish to " + topic, e);
        }
    }

    /**
     * The record, with the originating request's trace context replayed onto it.
     *
     * <p><strong>The stored traceparent, never the relay thread's own.</strong> This method runs on
     * a scheduler, seconds after the request that caused the event has finished and its span has
     * closed. Publishing under the scheduler's context would produce a second trace with nothing
     * joining it to the first, so a transaction would be followable through ingestion and followable
     * again from here, and never followable end to end — which is the whole thing Phase 7's gate
     * asks for. Replaying the stored value makes the consumer's work, and the scoring call it
     * causes, descendants of the API request.
     *
     * <p>Absent for a row written outside any trace — the seed, a scheduled path — and then no
     * header is written at all. An empty or fabricated one would be worse: the consumer would either
     * fail to parse it or start a trace that points nowhere.
     */
    private ProducerRecord<String, String> record(String topic, OutboxEvent event, String body) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, event.getPartitionKey(), body);
        String traceParent = event.getTraceParent();
        if (traceParent != null) {
            record.headers().add(TRACEPARENT_HEADER, traceParent.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    private String serialise(OutboxEvent event) {
        // The stored payload is JSON text. Parsing it back into a node before
        // wrapping means the envelope carries an object, not an escaped string
        // that every consumer would have to parse a second time.
        JsonNode payload = objectMapper.readTree(event.getPayload());
        return objectMapper.writeValueAsString(EventEnvelope.of(event, payload));
    }
}
