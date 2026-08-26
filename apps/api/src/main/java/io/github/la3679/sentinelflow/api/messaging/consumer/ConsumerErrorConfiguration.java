/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.messaging.consumer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;

/**
 * What happens when a listener throws: how often it is retried, and where it goes when it stops
 * being retried.
 *
 * <p>Spring Boot applies a single {@link org.springframework.kafka.listener.CommonErrorHandler} bean
 * to every listener container it builds, so the policy is declared once here rather than repeated on
 * each {@code @KafkaListener} — where the next listener would inevitably be the one that forgot it.
 *
 * <p><strong>Retries are blocking, and that is a deliberate trade.</strong> Spring Kafka's other
 * option is a non-blocking retry topic, which frees the partition immediately at the cost of losing
 * ordering for the retried record. Transaction events are keyed by account precisely so that one
 * account's events stay ordered (ADR-0006 §2), and velocity rules depend on that ordering — so a
 * retry that jumps a record ahead of or behind its account's other events would silently break the
 * guarantee the partitioning exists to provide. The retry budget is kept short for the same reason:
 * a blocking retry stalls everything queued behind it, so the ceiling here is seconds where the
 * relay's is minutes.
 */
@Configuration(proxyBeanMethods = false)
public class ConsumerErrorConfiguration {

    /**
     * @param recoverer where an exhausted or non-retryable record goes
     * @param retryState listens to every failed delivery so the dead-letter record can state how many
     *     attempts there were and when the first one failed, rather than guessing
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(
            DeadLetterRecoverer recoverer, RetryStateTracker retryState, ConsumerProperties properties) {

        DefaultErrorHandler handler = new DefaultErrorHandler(
                recoverer,
                new FullJitterBackOff(properties.retryBase(), properties.retryMaxDelay(), properties.maxAttempts()));

        // The classification ADR-0006 §4 requires, expressed where the container
        // can act on it. Without this a malformed record would be retried on the
        // same schedule as an unreachable dependency, failing identically every
        // time while the partition behind it waits.
        handler.addNotRetryableExceptions(NonRetryableEventException.class);

        handler.setRetryListeners(retryState);

        // Commit the offset once a record has been recovered. Left off, a
        // dead-lettered record is redelivered after every restart and
        // dead-lettered again, so the DLQ grows without anything new failing.
        handler.setCommitRecovered(true);

        return handler;
    }
}
