/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.observability;

import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import io.github.la3679.sentinelflow.api.domain.EventType;
import io.github.la3679.sentinelflow.api.messaging.EventTopics;
import io.github.la3679.sentinelflow.api.messaging.consumer.TransactionCreatedConsumer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Consumer lag and dead-letter depth, read from the broker on a schedule.
 *
 * <h2>What each number means, because both are easy to misread</h2>
 *
 * <p><strong>Lag</strong> is how many records the {@code transaction-risk} group has not yet read on
 * {@code transaction.created.v1}, summed across its three partitions. Summed rather than published
 * per partition (ADR-0016 §6): the first question is always "is the pipeline keeping up", and the
 * runbook reaches for {@code kafka-consumer-groups.sh} when the answer is no and the next question
 * is "which partition".
 *
 * <p><strong>Dead-letter depth is a depth, not a backlog of unhandled failures.</strong> Nothing
 * consumes {@code transaction.processing.dlq.v1} — an operator reads it by hand — so this number
 * does not fall when somebody deals with a record. It falls when the topic's thirty-day retention
 * expires. A rising value means new failures; a flat non-zero value means old ones are still inside
 * the retention window, and it is not evidence that anything is wrong today.
 *
 * <h2>A broker that does not answer holds the last reading</h2>
 *
 * The alternative — reporting zero — is the worst possible answer, because zero is also what a
 * healthy pipeline reports, and an alert that says "lag is fine" while the broker is unreachable is
 * an alert that hides an outage. A stale value at least keeps the last true number on the graph,
 * and a broker that is actually down is already visible in the health endpoint, in
 * {@code sentinelflow.outbox.pending} climbing, and in the log line below.
 *
 * <p>That log line is at {@code WARN} the first time and {@code DEBUG} while the failure persists.
 * A scheduled task that logs at {@code WARN} every fifteen seconds through an outage produces
 * hundreds of identical lines, and a log nobody reads is worse than one line nobody missed.
 */
public class KafkaPipelineMetrics {

    private static final Logger log = LoggerFactory.getLogger(KafkaPipelineMetrics.class);

    /** The metric names, here so the tests assert against the shipped strings. */
    static final String LAG_METRIC = "sentinelflow.kafka.consumer.lag";

    static final String DLQ_DEPTH_METRIC = "sentinelflow.kafka.dlq.depth";

    private final PipelineOffsets offsets;
    private final String consumerGroup;
    private final String consumedTopic;
    private final String deadLetterTopic;

    // Two series and no more: one group, one topic, one dead-letter topic, all
    // three fixed in code rather than derived from anything a caller sent
    // (ADR-0016 section 2).
    private final AtomicLong lag = new AtomicLong();
    private final AtomicLong deadLetterDepth = new AtomicLong();

    /** Whether the previous round failed, so the log says it once rather than every tick. */
    private volatile boolean broken;

    public KafkaPipelineMetrics(PipelineOffsets offsets, MeterRegistry meters) {
        this.offsets = offsets;
        this.consumerGroup = TransactionCreatedConsumer.CONSUMER_NAME;
        this.consumedTopic = EventTopics.topicFor(EventType.TRANSACTION_CREATED);
        this.deadLetterTopic = EventTopics.topicFor(EventType.TRANSACTION_PROCESSING_FAILED);

        Gauge.builder(LAG_METRIC, lag, AtomicLong::doubleValue)
                .tag("group", consumerGroup)
                .tag("topic", consumedTopic)
                .description("Records the consumer group has not yet read, summed across partitions")
                .register(meters);

        Gauge.builder(DLQ_DEPTH_METRIC, deadLetterDepth, AtomicLong::doubleValue)
                .tag("topic", deadLetterTopic)
                .description("Records the dead-letter topic holds within its retention window")
                .register(meters);
    }

    /**
     * One round of readings.
     *
     * <p>{@code fixedDelay}, not {@code fixedRate}: a rate schedules the next run from the start of
     * the last one, so a round that takes longer than the interval is immediately followed by
     * another, and a slow broker gets a queue of admin calls rather than a pause.
     */
    @Scheduled(
            fixedDelayString = "${sentinelflow.observability.kafka.refresh-interval:15s}",
            initialDelayString = "${sentinelflow.observability.kafka.refresh-interval:15s}")
    public void refresh() {
        try {
            long readLag = offsets.consumerLag(consumerGroup, consumedTopic);
            long readDepth = offsets.topicDepth(deadLetterTopic);

            // Both assigned only after both reads succeed. Assigning as each one
            // returns would publish a fresh lag beside a stale depth, which is
            // two readings from different moments presented as one snapshot.
            lag.set(readLag);
            deadLetterDepth.set(readDepth);

            if (broken) {
                log.info("The broker is answering offset queries again; lag is {}", readLag);
                broken = false;
            }
        } catch (PipelineOffsets.OffsetsUnavailableException unavailable) {
            if (broken) {
                log.debug("The broker still did not answer offset queries: {}", unavailable.getMessage());
            } else {
                log.warn(
                        "Could not read consumer lag or dead-letter depth; the gauges now hold a stale reading. {}",
                        unavailable.getMessage());
                broken = true;
            }
        }
    }

    /** The last successful lag reading. For tests, and for the health endpoint. */
    public long lag() {
        return lag.get();
    }

    /** The last successful dead-letter depth reading. For tests, and for the health endpoint. */
    public long deadLetterDepth() {
        return deadLetterDepth.get();
    }

    /** Whether the most recent round failed, so a caller can say the reading is stale. */
    public boolean stale() {
        return broken;
    }
}
