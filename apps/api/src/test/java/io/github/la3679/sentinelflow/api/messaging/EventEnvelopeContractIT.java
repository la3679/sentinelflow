/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.la3679.sentinelflow.api.domain.EventType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps the envelope, the topic map and their contracts from drifting.
 *
 * <p>An envelope field is in every message already written, and a topic name is in every consumer's
 * configuration. Neither is a refactor, and neither is checked by anything else in a Java build:
 * a schema file and an AsyncAPI document are data as far as the compiler is concerned.
 *
 * <p>An IT rather than a unit test for the same reason as
 * {@code TransactionCreatedContractIT}: it reads files above the module, and
 * {@code apps/api/Dockerfile} builds from a module-only context where those files do not exist.
 */
class EventEnvelopeContractIT {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static Path repositoryRoot() {
        return Path.of("").toAbsolutePath().getParent().getParent();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
    }

    private static JsonNode envelopeSchema() {
        return MAPPER.readTree(read(repositoryRoot().resolve("contracts/schemas/event-envelope.v1.json")));
    }

    @Test
    @DisplayName("the envelope record's fields are exactly the schema's properties")
    void envelopeMatchesTheSchema() {
        List<String> inSchema = new ArrayList<>();
        envelopeSchema().get("properties").propertyNames().forEach(inSchema::add);

        List<String> inRecord = new ArrayList<>();
        for (RecordComponent component : EventEnvelope.class.getRecordComponents()) {
            inRecord.add(component.getName());
        }

        assertThat(inRecord).containsExactlyInAnyOrderElementsOf(inSchema);
    }

    @Test
    @DisplayName("every envelope field is required, including the nullable ones")
    void everyEnvelopeFieldIsRequired() {
        JsonNode schema = envelopeSchema();
        List<String> properties = new ArrayList<>();
        schema.get("properties").propertyNames().forEach(properties::add);

        List<String> required = new ArrayList<>();
        schema.get("required").forEach(node -> required.add(node.asString()));

        // traceId is nullable and still required. Null rather than absent, so a
        // consumer never distinguishes "no trace context" from "the producer
        // omitted the field" - and the envelope is the one place that rule has
        // to hold for every event ever published.
        assertThat(required).containsExactlyInAnyOrderElementsOf(properties);
        assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("every event type has a topic, and every topic is one AsyncAPI declares")
    void topicsMatchTheAsyncApiDocument() {
        // A type without a topic fails at publication time - inside the relay,
        // on a row already committed, where the only symptom is an event that
        // never arrives.
        assertThat(EventTopics.all().keySet()).containsExactlyInAnyOrder(EventType.values());

        String asyncApi = read(repositoryRoot().resolve("contracts/asyncapi/sentinelflow-events.yaml"));
        for (String topic : EventTopics.all().values()) {
            assertThat(asyncApi)
                    .as("AsyncAPI declares a channel addressed %s", topic)
                    .contains("address: " + topic);
        }
    }

    @Test
    @DisplayName("every topic carries an explicit version suffix")
    void topicsAreVersioned() {
        // The suffix is the escape hatch ADR-0006 relies on: a breaking payload
        // change publishes to .v2 alongside .v1, so consumers migrate on their
        // own schedule instead of every one being redeployed at the same
        // instant as the producer.
        assertThat(EventTopics.all().values()).allMatch(topic -> topic.endsWith(".v1"));
    }
}
