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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps {@link AlertCreatedPayload} and {@code alert-created.v1.json} from drifting apart.
 *
 * <p>The same job {@code RiskAssessedContractIT} does for the other payload the risk workflow
 * publishes, and for the same reason: the schema declares {@code additionalProperties: false}, so a
 * field on the record and not in the schema is a message every conforming consumer must reject, and
 * a field in the schema and not on the record is a promise no producer keeps.
 *
 * <p>It also pins the one constraint this payload has that the others do not: {@code status} is
 * {@code const: "NEW"}. A created event in a later state would be a producer bug the schema is
 * written to make impossible, and the assertion here is that the schema still says so.
 *
 * <p><strong>An IT despite needing no container</strong>, for the reason
 * {@code TransactionCreatedContractIT} records: it reads a file two directories above the module,
 * and {@code apps/api/Dockerfile} builds from a module-only context where that path does not exist.
 */
class AlertCreatedContractIT {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static JsonNode read(String relativePath) {
        // Failing loudly when the file is absent rather than skipping. A
        // contract test that quietly skips because it could not find the
        // contract reports green for the one defect it exists to catch, which
        // is how twelve assertions vanished from the scoring suite once.
        Path repositoryRoot = Path.of("").toAbsolutePath().getParent().getParent();
        Path path = repositoryRoot.resolve(relativePath);
        try {
            return MAPPER.readTree(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
    }

    private static JsonNode schema() {
        return read("contracts/schemas/alert-created.v1.json");
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
        assertThat(componentNames(AlertCreatedPayload.class))
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

        // modelScore, modelVersion and featureVersion are nullable and still
        // required: null rather than absent, so a consumer never has to
        // distinguish "the model did not answer" from "the producer forgot the
        // field". degraded says which it was.
        assertThat(required).containsExactlyInAnyOrderElementsOf(propertyNames(schema.get("properties")));
    }

    @Test
    @DisplayName("the schema still forbids additional properties")
    void schemaIsClosed() {
        assertThat(schema().get("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("a created alert is pinned to NEW by the schema, not merely documented as NEW")
    void createdAlertsAreAlwaysNew() {
        // A const rather than a sentence. A producer bug that emitted a created
        // event in a later state would otherwise be a valid message, and the
        // first thing to notice would be a queue view showing an alert that had
        // already been closed before anybody saw it.
        assertThat(schema().get("properties").get("status").get("const").asString())
                .isEqualTo("NEW");
    }
}
