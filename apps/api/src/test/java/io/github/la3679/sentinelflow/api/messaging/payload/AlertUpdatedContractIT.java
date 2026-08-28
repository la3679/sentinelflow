/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.messaging.payload;

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

import io.github.la3679.sentinelflow.api.domain.AlertChangeType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps {@link AlertUpdatedPayload} and {@code alert-updated.v1.json} from drifting apart.
 *
 * <p>The same job {@code AlertCreatedContractIT} does for the other half of the alert lifecycle, and
 * for the same reason: {@code additionalProperties: false} means a field on the record and not in
 * the schema is a message every conforming consumer must reject.
 *
 * <p>It also pins the two constraints particular to this payload: {@code changeType} enumerates
 * exactly the change kinds the producer can emit, and {@code version} has a minimum of 1 because an
 * event describing a change can only exist after one has been made.
 *
 * <p><strong>An IT despite needing no container</strong>, for the reason the sibling tests record:
 * it reads a file two directories above the module, and {@code apps/api/Dockerfile} builds from a
 * module-only context where that path does not exist.
 */
class AlertUpdatedContractIT {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static JsonNode schema() {
        Path repositoryRoot = Path.of("").toAbsolutePath().getParent().getParent();
        Path path = repositoryRoot.resolve("contracts/schemas/alert-updated.v1.json");
        try {
            return MAPPER.readTree(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
    }

    private static List<String> propertyNames(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.propertyNames().forEach(names::add);
        return names;
    }

    private static List<String> componentNames(Class<?> record) {
        List<String> names = new ArrayList<>();
        for (RecordComponent component : record.getRecordComponents()) {
            names.add(component.getName());
        }
        return names;
    }

    @Test
    @DisplayName("the payload's fields are exactly the schema's properties")
    void payloadMatchesTheSchemaProperties() {
        assertThat(componentNames(AlertUpdatedPayload.class))
                .as("a field here and not in the schema is rejected by every conforming consumer, "
                        + "because the schema sets additionalProperties: false")
                .containsExactlyInAnyOrderElementsOf(propertyNames(schema().get("properties")));
    }

    @Test
    @DisplayName("every schema property is required, so no consumer has to handle an absent field")
    void everyPropertyIsRequired() {
        JsonNode schema = schema();

        List<String> required = new ArrayList<>();
        schema.get("required").forEach(node -> required.add(node.asString()));

        // previousAssignee and assignee are nullable and still required: null
        // rather than absent, so a consumer never distinguishes "nobody holds
        // it" from "the producer forgot the field".
        assertThat(required).containsExactlyInAnyOrderElementsOf(propertyNames(schema.get("properties")));
    }

    @Test
    @DisplayName("the schema still forbids additional properties")
    void schemaIsClosed() {
        assertThat(schema().get("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("the change types the producer can emit are exactly the ones the schema allows")
    void changeTypesAgree() {
        List<String> allowed = new ArrayList<>();
        schema().get("properties").get("changeType").get("enum").forEach(node -> allowed.add(node.asString()));

        assertThat(List.of(AlertChangeType.values()).stream().map(Enum::name).toList())
                .as("a change type this service can produce and the schema does not name would be "
                        + "an event no consumer could route")
                .containsExactlyInAnyOrderElementsOf(allowed);
    }

    @Test
    @DisplayName("an update can only describe an alert that has already changed at least once")
    void versionStartsAtOne() {
        // An alert is raised at version 0, so 0 on this event would describe a
        // change that had not been written. The REST contract's Alert.version
        // deliberately allows 0 and this deliberately does not; they are the
        // same counter answering two different questions.
        assertThat(schema().get("properties").get("version").get("minimum").asInt())
                .isEqualTo(1);
    }
}
