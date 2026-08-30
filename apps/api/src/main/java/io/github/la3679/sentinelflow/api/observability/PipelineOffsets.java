/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.observability;

/**
 * The two questions this application asks the broker about itself.
 *
 * <p><strong>A narrow port rather than an {@code Admin} passed around.</strong> Kafka's admin
 * interface is forty methods returning futures of result objects, and a component that holds one is
 * a component that can only be tested against a broker. Two methods returning a {@code long} can be
 * faked in a unit test — which is how {@link KafkaPipelineMetrics} is tested for the behaviour that
 * actually matters, namely what the gauges read when the broker does not answer.
 *
 * <p>The implementation that ships is {@link KafkaAdminPipelineOffsets}, and it is exercised against
 * a real broker in its own integration test. Both halves are needed: a fake proves the degradation
 * policy, a real broker proves the arithmetic.
 */
public interface PipelineOffsets {

    /**
     * How many records a consumer group has not yet read on a topic, summed across its partitions.
     *
     * <p>A partition the group has never committed an offset for counts from the topic's earliest
     * available offset, because {@code auto-offset-reset} is {@code earliest} — so the number
     * matches what the group will actually have to read, rather than reporting zero for a group that
     * has not started.
     *
     * @throws OffsetsUnavailableException if the broker did not answer within the budget
     */
    long consumerLag(String consumerGroup, String topic);

    /**
     * How many records a topic currently holds, summed across its partitions.
     *
     * <p>End offset minus start offset, so it is records <em>within the retention window</em> rather
     * than records ever written. Used for the dead-letter topic, where nothing consumes and the
     * number therefore falls only when retention expires.
     *
     * @throws OffsetsUnavailableException if the broker did not answer within the budget
     */
    long topicDepth(String topic);

    /** The broker did not answer. Never a reason to fail a caller — only to keep the last reading. */
    class OffsetsUnavailableException extends RuntimeException {

        public OffsetsUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
