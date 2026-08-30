/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.observability;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.admin.Admin;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Wires the broker readings, and only where there is a broker to read.
 *
 * <p>The condition mirrors the one on {@code TransactionCreatedConsumer} and exists for the same
 * reason: a great many tests need the schema and no Kafka, and a scheduled task hammering an address
 * that does not resolve turns every one of their runs into a wall of connection warnings. On by
 * default, because an observability component that has to be switched on is one that is off in the
 * environment that needed it.
 *
 * <p><strong>The admin client is built from {@link KafkaAdmin}'s own configuration</strong> rather
 * than from a second copy of the bootstrap address. One place decides where the broker is; a
 * duplicate property is a thing that can disagree, and it would disagree silently — the gauges would
 * simply stop updating against a broker everything else could reach.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "sentinelflow.observability.kafka",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class KafkaPipelineMetricsConfiguration {

    /**
     * The admin client, closed by Spring on shutdown.
     *
     * <p>{@code destroyMethod} is named explicitly: {@link Admin} extends {@code AutoCloseable}, and
     * an unclosed admin client keeps its own network threads and its own connection to the broker
     * alive past the context that made it — which in a test suite means one leaked client per
     * context.
     */
    @Bean(destroyMethod = "close")
    Admin sentinelflowAdminClient(KafkaAdmin kafkaAdmin) {
        Map<String, Object> configuration = new HashMap<>(kafkaAdmin.getConfigurationProperties());
        return Admin.create(configuration);
    }

    @Bean
    PipelineOffsets pipelineOffsets(Admin sentinelflowAdminClient, KafkaPipelineMetricsProperties properties) {
        return new KafkaAdminPipelineOffsets(sentinelflowAdminClient, properties.timeout());
    }

    @Bean
    KafkaPipelineMetrics kafkaPipelineMetrics(PipelineOffsets pipelineOffsets, MeterRegistry meters) {
        return new KafkaPipelineMetrics(pipelineOffsets, meters);
    }
}
