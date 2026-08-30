/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.observability;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

/**
 * {@link PipelineOffsets} over Kafka's admin client.
 *
 * <p><strong>Why the API asks rather than a {@code kafka-exporter} container</strong> is ADR-0016
 * §6: this application already holds a configured connection to the broker and already publishes a
 * Prometheus endpoint, and the alternative is another image at another version for two numbers.
 *
 * <p>Every call is bounded by the configured timeout and every failure becomes one
 * {@link PipelineOffsets.OffsetsUnavailableException}. Nothing here retries: this runs on a
 * schedule, so the retry is the next tick, and a retry loop inside a scheduled task is how one slow
 * broker turns into overlapping runs.
 */
public class KafkaAdminPipelineOffsets implements PipelineOffsets {

    private final Admin admin;
    private final Duration timeout;

    public KafkaAdminPipelineOffsets(Admin admin, Duration timeout) {
        this.admin = admin;
        this.timeout = timeout;
    }

    @Override
    public long consumerLag(String consumerGroup, String topic) {
        Map<TopicPartition, OffsetAndMetadata> committed = await(
                admin.listConsumerGroupOffsets(consumerGroup).partitionsToOffsetAndMetadata(),
                "committed offsets for group " + consumerGroup);

        List<TopicPartition> partitions = partitionsOf(topic);

        // Earliest as well as latest, because a partition the group has never
        // committed is not zero lag - the group is configured to start at
        // earliest, so what it owes is everything the broker still holds.
        Map<TopicPartition, Long> earliest = offsets(partitions, OffsetSpec.earliest());
        Map<TopicPartition, Long> latest = offsets(partitions, OffsetSpec.latest());

        long lag = 0;
        for (TopicPartition partition : partitions) {
            long end = latest.getOrDefault(partition, 0L);
            OffsetAndMetadata position = committed.get(partition);
            long from = position == null ? earliest.getOrDefault(partition, 0L) : position.offset();
            // Clamped at zero. A committed offset ahead of the end offset is
            // possible for a moment after a topic is recreated, and a negative
            // lag on a dashboard is a number somebody has to explain.
            lag += Math.max(0, end - from);
        }
        return lag;
    }

    @Override
    public long topicDepth(String topic) {
        List<TopicPartition> partitions = partitionsOf(topic);
        Map<TopicPartition, Long> earliest = offsets(partitions, OffsetSpec.earliest());
        Map<TopicPartition, Long> latest = offsets(partitions, OffsetSpec.latest());

        long depth = 0;
        for (TopicPartition partition : partitions) {
            depth += Math.max(0, latest.getOrDefault(partition, 0L) - earliest.getOrDefault(partition, 0L));
        }
        return depth;
    }

    private List<TopicPartition> partitionsOf(String topic) {
        TopicDescription description = await(
                        admin.describeTopics(List.of(topic)).allTopicNames(), "description of topic " + topic)
                .get(topic);
        if (description == null) {
            throw new OffsetsUnavailableException("The broker did not describe topic " + topic, null);
        }
        return description.partitions().stream()
                .map(partition -> new TopicPartition(topic, partition.partition()))
                .toList();
    }

    private Map<TopicPartition, Long> offsets(List<TopicPartition> partitions, OffsetSpec spec) {
        Map<TopicPartition, OffsetSpec> request = new HashMap<>();
        partitions.forEach(partition -> request.put(partition, spec));

        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> answered =
                await(admin.listOffsets(request).all(), "offsets for " + partitions.size() + " partitions");

        Map<TopicPartition, Long> byPartition = new HashMap<>();
        answered.forEach((partition, info) -> byPartition.put(partition, info.offset()));
        return byPartition;
    }

    private <T> T await(org.apache.kafka.common.KafkaFuture<T> future, String what) {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            // The scheduler is shutting down. Restore the flag rather than
            // swallowing it, and report unavailable rather than a stale zero.
            Thread.currentThread().interrupt();
            throw new OffsetsUnavailableException("Interrupted while reading " + what, interrupted);
        } catch (ExecutionException | TimeoutException failed) {
            throw new OffsetsUnavailableException("The broker did not answer with " + what + " in " + timeout, failed);
        }
    }
}
