/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.la3679.sentinelflow.api.support.KafkaContainerSupport;

/**
 * The offset arithmetic, against a real broker.
 *
 * <p><strong>No Spring context.</strong> Nothing here needs one: the class under test takes an
 * {@code Admin} and a timeout, and standing up an application to reach it would make a two-second
 * test a twenty-second one and would couple it to the schema. The broker is the same container
 * every other messaging suite uses.
 *
 * <p>What is worth asserting here, and could not be asserted against the fake in
 * {@code KafkaPipelineMetricsTests}: that a group which has committed nothing is reported as owing
 * the whole topic rather than as caught up, which is the single most misleading thing a lag metric
 * can get wrong. A group starting at {@code earliest} with no committed offsets is exactly the state
 * a fresh deployment is in, and a dashboard reporting zero for it says the pipeline is keeping up
 * with work it has not begun.
 */
class KafkaAdminPipelineOffsetsIT {

    private static final String GROUP = "offsets-it-group";

    private static Admin admin;
    private static KafkaAdminPipelineOffsets offsets;
    private static String topic;

    @BeforeAll
    static void connect() throws Exception {
        // Referencing the support class is what starts the container: its static
        // initialiser owns the broker, and this suite has no Spring context to
        // import it into.
        String bootstrap = KafkaContainerSupport.bootstrapServers();

        admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap));
        offsets = new KafkaAdminPipelineOffsets(admin, Duration.ofSeconds(10));

        topic = "offsets.it." + UUID.randomUUID();
        admin.createTopics(List.of(new NewTopic(topic, 3, (short) 1))).all().get();

        publish(bootstrap, 9);
    }

    @AfterAll
    static void disconnect() {
        if (admin != null) {
            admin.close();
        }
    }

    @Test
    @DisplayName("a topic's depth is what it holds, summed across its partitions")
    void readsDepthAcrossPartitions() {
        assertThat(offsets.topicDepth(topic))
                .as("end offset minus start offset, so it is records inside the retention window "
                        + "rather than records ever written")
                .isEqualTo(9);
    }

    @Test
    @DisplayName("a group that has committed nothing owes the whole topic, not zero")
    void treatsAnUncommittedGroupAsOwingEverything() {
        assertThat(offsets.consumerLag(GROUP, topic))
                .as("the group is configured to start at earliest, so reporting zero would say "
                        + "the pipeline is keeping up with work it has not begun")
                .isEqualTo(9);
    }

    @Test
    @DisplayName("lag falls as a group commits, and reaches zero when it has read everything")
    void followsWhatTheGroupHasCommitted() {
        String group = "offsets-it-consuming-" + UUID.randomUUID();

        Properties configuration = new Properties();
        configuration.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaContainerSupport.bootstrapServers());
        configuration.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        configuration.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        configuration.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        configuration.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configuration.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(configuration)) {
            consumer.subscribe(List.of(topic));

            int read = 0;
            // Poll until everything published is read. A single poll returns
            // whatever one fetch happened to bring back, and asserting on that
            // is how a test starts failing on a slower machine.
            while (read < 9) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(5));
                if (records.isEmpty()) {
                    break;
                }
                read += records.count();
            }
            consumer.commitSync();

            assertThat(read).isEqualTo(9);
            assertThat(offsets.consumerLag(group, topic)).isZero();
        }
    }

    @Test
    @DisplayName("a topic that does not exist is unavailability, never a silent zero")
    void refusesToInventANumberForAMissingTopic() {
        assertThatThrownBy(() -> offsets.topicDepth("offsets.it.absent." + UUID.randomUUID()))
                .as("a zero here would be indistinguishable from an empty dead-letter topic, "
                        + "which is the healthiest reading there is")
                .isInstanceOf(PipelineOffsets.OffsetsUnavailableException.class);
    }

    private static void publish(String bootstrap, int count) throws Exception {
        Properties configuration = new Properties();
        configuration.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        configuration.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configuration.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configuration.put(ProducerConfig.ACKS_CONFIG, "all");

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(configuration)) {
            for (int record = 0; record < count; record++) {
                // Keyed, so the records spread over the three partitions and the
                // sum across them is what is being asserted rather than one
                // partition's count.
                producer.send(new ProducerRecord<>(topic, "key-" + record, "{}"))
                        .get();
            }
        }
    }
}
