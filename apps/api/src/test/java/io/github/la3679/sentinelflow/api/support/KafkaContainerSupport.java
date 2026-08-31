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

    /**
     * Freezes the broker, so a drill can watch what happens while it is not answering.
     *
     * <p><strong>Pause, not stop, and the reason is the address.</strong> {@code stop()} on a
     * Testcontainers container removes it, and the {@code start()} that follows creates a new one on
     * a new ephemeral host port with an empty log — which is not a broker restart, it is a different
     * broker, and every producer and consumer in the context is still configured for the old
     * address. Going under Testcontainers to {@code docker stop} and {@code docker start} does not
     * help either: Docker re-picks an ephemeral host port on each start, which was confirmed rather
     * than assumed. {@code docker pause} freezes the processes through the cgroup freezer and
     * touches neither the port mapping nor the log, so the broker that comes back is the one that
     * went away.
     *
     * <p>What that simulates is a broker that has stopped answering — a stalled disk, a stop-the-
     * world pause, a partition on the broker's side. It does not simulate a connection refusal:
     * the kernel in the container's network namespace still completes handshakes into the accept
     * backlog while the process is frozen, so a client sees a request that never gets a reply rather
     * than an immediate rejection. Both end at the same place for this project — the producer's
     * {@code delivery.timeout.ms} expires and {@code KafkaEventPublisher} throws
     * {@code EventPublicationException} — and a drill that uses this must compress that timeout or
     * it will wait twenty seconds per attempt.
     *
     * <p><strong>Every caller must resume it.</strong> This is the one broker in the JVM fork, so a
     * drill that leaves it frozen fails every messaging suite that runs after it. Resume in a
     * {@code finally} and again in an {@code @AfterAll}; {@link #resumeBroker()} is safe to call when
     * the broker is already running.
     */
    public static void pauseBroker() {
        if (!isBrokerPaused()) {
            KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
        }
    }

    /** Unfreezes the broker. A no-op when it is not frozen, so it is safe in a {@code finally}. */
    public static void resumeBroker() {
        if (isBrokerPaused()) {
            KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
        }
    }

    /** Read from Docker rather than from a flag this class keeps, so it cannot drift from the truth. */
    public static boolean isBrokerPaused() {
        return Boolean.TRUE.equals(KAFKA.getCurrentContainerInfo().getState().getPaused());
    }
}
