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
 * Keeps the payload record and its JSON Schema from drifting apart.
 *
 * <p>{@code transaction-created.v1.json} declares {@code additionalProperties: false}, so a field
 * added to the record and not to the schema is a message every conforming consumer must reject —
 * and nothing in a Java build would notice, because a schema file is data as far as the compiler is
 * concerned. A field added to the schema and not to the record is the mirror failure: consumers are
 * promised something no producer sends.
 *
 * <p>This compares names, not types. CI already validates concrete examples against the schema
 * through {@code scripts/dev/check-contracts.mjs}, and {@code TransactionIngestionIT} asserts the
 * JSON types of the values a real ingestion produces. What none of those catch is a rename or an
 * addition, which is exactly what this covers — and it does so without adding a schema-validation
 * library to the runtime for one assertion.
 */
class TransactionCreatedPayloadTests {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static JsonNode schema() {
        // Resolved relative to the module, so this works in CI and in a clone
        // at any path.
        Path path = Path.of("")
                .toAbsolutePath()
                .getParent()
                .getParent()
                .resolve("contracts/schemas/transaction-created.v1.json");
        try {
            return MAPPER.readTree(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
    }

    @Test
    @DisplayName("the record's fields are exactly the schema's properties")
    void recordMatchesTheSchemaProperties() {
        List<String> inSchema = new ArrayList<>();
        schema().get("properties").propertyNames().forEach(inSchema::add);

        List<String> inRecord = new ArrayList<>();
        for (RecordComponent component : TransactionCreatedPayload.class.getRecordComponents()) {
            inRecord.add(component.getName());
        }

        assertThat(inRecord)
                .as("a field here and not in the schema is rejected by every conforming consumer, "
                        + "because the schema sets additionalProperties: false")
                .containsExactlyInAnyOrderElementsOf(inSchema);
    }

    @Test
    @DisplayName("every schema property is required, so no consumer has to handle an absent field")
    void everyPropertyIsRequired() {
        JsonNode schema = schema();
        List<String> properties = new ArrayList<>();
        schema.get("properties").propertyNames().forEach(properties::add);

        List<String> required = new ArrayList<>();
        schema.get("required").forEach(node -> required.add(node.asString()));

        // deviceReference is nullable and still required: null rather than
        // absent, so a consumer never distinguishes "this channel has no
        // device" from "the producer forgot the field". This asserts that
        // principle holds for the whole payload rather than only where someone
        // remembered it.
        assertThat(required).containsExactlyInAnyOrderElementsOf(properties);
    }

    @Test
    @DisplayName("the schema still forbids additional properties")
    void schemaIsClosed() {
        // If this is ever relaxed, the first test above stops being a
        // contract check and becomes a style preference.
        assertThat(schema().get("additionalProperties").asBoolean()).isFalse();
    }
}
