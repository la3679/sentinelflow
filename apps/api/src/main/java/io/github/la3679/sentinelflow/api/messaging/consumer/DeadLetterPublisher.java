/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.messaging.consumer;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import io.github.la3679.sentinelflow.api.domain.EventType;
import io.github.la3679.sentinelflow.api.messaging.EventPublicationException;
import io.github.la3679.sentinelflow.api.messaging.EventTopics;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes a dead-letter record to {@code transaction.processing.dlq.v1}.
 *
 * <p><strong>Keyed with the original message's key</strong>, so a failed record stays in the same
 * partition as the traffic it came from. Rekeying would scatter one account's failures across
 * partitions and lose the ordering that made the original key worth choosing (ADR-0006 §2).
 *
 * <p><strong>Synchronous, and it throws.</strong> The caller is a recoverer, and a recoverer that
 * returns successfully tells Spring Kafka to commit the offset. Returning on the future rather than
 * on the acknowledgement would let a record be marked handled while its dead-letter write was still
 * in flight and possibly failing — the one outcome that loses an event permanently, since nothing
 * else is holding it any more.
 */
@Component
public class DeadLetterPublisher {

    private static final long SEND_TIMEOUT_SECONDS = 30;

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;

    public DeadLetterPublisher(KafkaTemplate<String, String> kafka, ObjectMapper objectMapper) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
    }

    /**
     * @param originalKey the failed record's key, or null if it had none
     * @throws EventPublicationException if the broker did not acknowledge
     */
    public void publish(String originalKey, DeadLetterRecord record) {
        String topic = EventTopics.topicFor(EventType.TRANSACTION_PROCESSING_FAILED);
        String body = objectMapper.writeValueAsString(record);

        try {
            kafka.send(topic, originalKey, body).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventPublicationException("Interrupted while dead-lettering to " + topic, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new EventPublicationException("Broker did not acknowledge the dead-letter write to " + topic, e);
        } catch (RuntimeException e) {
            // send() throws synchronously when the producer cannot fetch
            // metadata at all, which escapes the two checked cases above. The
            // same defect KafkaEventPublisher was written around.
            throw new EventPublicationException("Could not dead-letter to " + topic, e);
        }
    }
}
