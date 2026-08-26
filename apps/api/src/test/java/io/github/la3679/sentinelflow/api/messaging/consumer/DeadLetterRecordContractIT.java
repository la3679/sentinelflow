/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.la3679.sentinelflow.api.domain.DlqFailureClass;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps the dead-letter record and its schema from drifting.
 *
 * <p>A schema file is data as far as the compiler is concerned, so nothing else in a Java build
 * notices when a field is added to one side and not the other. {@code dlq-record.v1.json} declares
 * {@code additionalProperties: false}, which makes an unmatched field here not an addition but a
 * record every conforming consumer must reject.
 *
 * <p>An IT rather than a unit test for the same reason as {@code EventEnvelopeContractIT}: it reads
 * files above the module, and {@code apps/api/Dockerfile} builds from a module-only context where
 * those files do not exist.
 */
class DeadLetterRecordContractIT {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static Path repositoryRoot() {
        return Path.of("").toAbsolutePath().getParent().getParent();
    }

    private static JsonNode schema() {
        Path path = repositoryRoot().resolve("contracts/schemas/dlq-record.v1.json");
        try {
            return MAPPER.readTree(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
    }

    @Test
    @DisplayName("the record's fields are exactly the schema's properties")
    void fieldsMatchTheSchema() {
        List<String> inSchema = new ArrayList<>();
        schema().get("properties").propertyNames().forEach(inSchema::add);

        List<String> inRecord = Arrays.stream(DeadLetterRecord.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(inRecord).containsExactlyInAnyOrderElementsOf(inSchema);
    }

    @Test
    @DisplayName("every field is required, so a reader never has to test for absence")
    void everyFieldIsRequired() {
        JsonNode schema = schema();
        List<String> properties = new ArrayList<>();
        schema.get("properties").propertyNames().forEach(properties::add);

        List<String> required = new ArrayList<>();
        schema.get("required").forEach(node -> required.add(node.asString()));

        assertThat(required).containsExactlyInAnyOrderElementsOf(properties);
        assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("the failure classes are exactly the schema's enum")
    void failureClassesMatchTheSchema() {
        List<String> inSchema = new ArrayList<>();
        schema().get("properties").get("failureClass").get("enum").forEach(node -> inSchema.add(node.asString()));

        List<String> inCode =
                Arrays.stream(DlqFailureClass.values()).map(Enum::name).toList();

        // Both directions. A sixth value in the code would produce records a
        // conforming consumer rejects; one only in the schema is a case nothing
        // can ever write, which is a different kind of lie about the contract.
        assertThat(inCode).containsExactlyInAnyOrderElementsOf(inSchema);
    }

    @Test
    @DisplayName("the schema bounds the sanitised message at the length the sanitiser enforces")
    void messageBoundsAgree() {
        int schemaBound = schema().get("properties")
                .get("sanitisedMessage")
                .get("maxLength")
                .asInt();

        // Two numbers in two files that must be the same one. If the schema is
        // relaxed and the sanitiser is not, records are shorter than they need
        // to be; the other way round, every long message is rejected at the
        // consumer instead of at the producer.
        assertThat(FailureSanitiser.MAX_MESSAGE_LENGTH).isEqualTo(schemaBound);
    }
}
