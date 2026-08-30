/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * What the gauges read, including when the broker does not answer.
 *
 * <p><strong>A fake {@link PipelineOffsets} rather than a broker</strong>, because the behaviour
 * under test is the degradation policy and not the arithmetic. Making a real broker fail on demand
 * means stopping a container mid-suite; making this one fail is a boolean. The arithmetic against a
 * real broker is {@code KafkaAdminPipelineOffsetsIT}'s job, and neither test would catch the other's
 * defect.
 */
class KafkaPipelineMetricsTests {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final FakeOffsets offsets = new FakeOffsets();

    @Test
    @DisplayName("a reading reaches both gauges")
    void publishesWhatItRead() {
        offsets.lag = 42;
        offsets.depth = 7;

        new KafkaPipelineMetrics(offsets, meters).refresh();

        assertThat(gauge(KafkaPipelineMetrics.LAG_METRIC)).isEqualTo(42);
        assertThat(gauge(KafkaPipelineMetrics.DLQ_DEPTH_METRIC)).isEqualTo(7);
    }

    @Test
    @DisplayName("a broker that stops answering holds the last reading rather than reporting zero")
    void keepsTheLastReadingWhenTheBrokerIsGone() {
        offsets.lag = 900;
        offsets.depth = 3;

        KafkaPipelineMetrics metrics = new KafkaPipelineMetrics(offsets, meters);
        metrics.refresh();

        offsets.broken = true;
        metrics.refresh();

        assertThat(gauge(KafkaPipelineMetrics.LAG_METRIC))
                .as("zero is what a healthy pipeline reports, so reporting it for an unreachable "
                        + "broker is an alert rule saying everything is fine during an outage")
                .isEqualTo(900);
        assertThat(gauge(KafkaPipelineMetrics.DLQ_DEPTH_METRIC)).isEqualTo(3);
        assertThat(metrics.stale()).isTrue();
    }

    @Test
    @DisplayName("nothing is published from a round where only the first read succeeded")
    void doesNotMixReadingsFromDifferentMoments() {
        offsets.lag = 5;
        offsets.depth = 5;
        KafkaPipelineMetrics metrics = new KafkaPipelineMetrics(offsets, meters);
        metrics.refresh();

        offsets.lag = 600;
        offsets.breakOnDepthOnly = true;
        metrics.refresh();

        assertThat(gauge(KafkaPipelineMetrics.LAG_METRIC))
                .as("a fresh lag beside a stale depth is two readings from different moments "
                        + "presented as one snapshot")
                .isEqualTo(5);
        assertThat(gauge(KafkaPipelineMetrics.DLQ_DEPTH_METRIC)).isEqualTo(5);
    }

    @Test
    @DisplayName("a broker that comes back is read again, and the staleness clears")
    void recovers() {
        KafkaPipelineMetrics metrics = new KafkaPipelineMetrics(offsets, meters);

        offsets.broken = true;
        metrics.refresh();
        assertThat(metrics.stale()).isTrue();

        offsets.broken = false;
        offsets.lag = 11;
        offsets.depth = 2;
        metrics.refresh();

        assertThat(metrics.stale()).isFalse();
        assertThat(metrics.lag()).isEqualTo(11);
        assertThat(metrics.deadLetterDepth()).isEqualTo(2);
    }

    @Test
    @DisplayName("the labels are the group and the two topic names, and nothing else")
    void keepsTheLabelSpaceClosed() {
        new KafkaPipelineMetrics(offsets, meters);

        assertThat(meters.getMeters())
                .filteredOn(meter -> meter.getId().getName().startsWith("sentinelflow.kafka"))
                .hasSize(2)
                .allSatisfy(meter -> assertThat(meter.getId().getTags())
                        .as("ADR-0016 section 6: summed across partitions, so a partition number "
                                + "is not a label here either")
                        .allSatisfy(tag -> assertThat(tag.getKey()).isIn("group", "topic")));
    }

    private double gauge(String name) {
        Gauge gauge = meters.find(name).gauge();
        assertThat(gauge).as("no gauge named %s", name).isNotNull();
        return gauge.value();
    }

    /** A broker that answers with whatever the test set, or refuses to answer at all. */
    private static final class FakeOffsets implements PipelineOffsets {

        private long lag;
        private long depth;
        private boolean broken;
        private boolean breakOnDepthOnly;

        @Override
        public long consumerLag(String consumerGroup, String topic) {
            if (broken) {
                throw new OffsetsUnavailableException("the fake broker is not answering", null);
            }
            return lag;
        }

        @Override
        public long topicDepth(String topic) {
            if (broken || breakOnDepthOnly) {
                throw new OffsetsUnavailableException("the fake broker is not answering", null);
            }
            return depth;
        }
    }
}
