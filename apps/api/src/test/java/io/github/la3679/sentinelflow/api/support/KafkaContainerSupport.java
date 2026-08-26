/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The one Kafka broker the messaging suites run against.
 *
 * <p><strong>A real broker, not a mock.</strong> The only thing worth testing about the publisher is
 * what it does when the broker does not acknowledge, and a mocked {@code KafkaTemplate} answers that
 * question by construction rather than by behaviour. It also means the serialiser, the partition
 * key, and the record the consumer actually receives are the real ones.
 *
 * <p>One container per JVM fork, started once and left to Ryuk to remove, for the same reason
 * {@link PostgresContainerSupport} does it: Spring's test-context cache keys on configuration, so
 * distinct contexts would otherwise each pay a broker start.
 *
 * <p>The image comes from the {@code kafka.test.image} pom property and matches {@code
 * compose.yaml}. Apache's own KRaft image, so there is no ZooKeeper anywhere in this project.
 */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaContainerSupport {

    private static final String IMAGE = System.getProperty("sentinelflow.test.kafka.image", "apache/kafka:4.2.1");

    private static final KafkaContainer KAFKA = new KafkaContainer(IMAGE);

    static {
        KAFKA.start();
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return KAFKA;
    }

    /**
     * Where the broker actually is.
     *
     * <p>Not readable from {@code ${spring.kafka.bootstrap-servers}}: {@code @ServiceConnection}
     * supplies a connection-details bean that the Kafka autoconfiguration prefers, and it does not
     * rewrite the property. A test reading that placeholder gets {@code kafka:9092} from
     * {@code application.yaml} - the compose service name, which resolves to nothing here. Found by
     * a consumer that could not be constructed.
     */
    public static String bootstrapServers() {
        return KAFKA.getBootstrapServers();
    }
}
