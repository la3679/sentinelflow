/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import io.github.la3679.sentinelflow.api.domain.TransactionChannel;
import io.github.la3679.sentinelflow.api.domain.TransactionType;
import io.github.la3679.sentinelflow.api.scoring.payload.AccountContext;
import io.github.la3679.sentinelflow.api.scoring.payload.Amount;
import io.github.la3679.sentinelflow.api.scoring.payload.RecentTransaction;
import io.github.la3679.sentinelflow.api.scoring.payload.ScoreRequest;
import io.github.la3679.sentinelflow.api.scoring.payload.TransactionToScore;

/**
 * Keeps the scoring payload records and {@code sentinelflow-scoring.yaml} from drifting apart.
 *
 * <p>Every one of these schemas declares {@code additionalProperties: false}, so a field added to a
 * record and not to the contract is not an addition — it is a request the scoring service is
 * required to reject with a 422. Nothing in a Java build notices, because a YAML file is data as far
 * as the compiler is concerned. The mirror failure is a field added to the contract and not to the
 * record: the service is promised something the API never sends, and the first symptom is a
 * validation error naming a field nobody has heard of.
 *
 * <p>This asserts names, not types. {@code scripts/dev/check-contracts.mjs} validates the document
 * itself in CI, and {@code apps/scoring}'s {@code test_schema_matches_contract.py} holds the Python
 * models to the same file — so the two implementations are pinned to the contract rather than to
 * each other, which is the only arrangement where both can be wrong and someone finds out.
 *
 * <p><strong>An IT despite needing no container</strong>, for the reason
 * {@code TransactionCreatedContractIT} records: it reads a file two directories above the module,
 * and {@code apps/api/Dockerfile} builds from a module-only context where a unit test reaching for
 * {@code ../../contracts} fails on a null path. That was found by it happening.
 *
 * <p>There is deliberately <strong>no skip</strong> when the contract file is absent. A contract
 * test that quietly skips because it could not find the contract reports green for the one failure
 * it exists to catch — which is how twelve assertions once vanished from this repository's scoring
 * suite for a path that was wrong by one level.
 */
class ScoringPayloadContractIT {

    private static final String CONTRACT = "contracts/openapi/sentinelflow-scoring.yaml";

    @Test
    @DisplayName("Amount's fields are exactly the contract's")
    void amountMatchesTheContract() {
        assertRecordMatchesSchema(Amount.class, "Amount");
    }

    @Test
    @DisplayName("RecentTransaction's fields are exactly the contract's")
    void recentTransactionMatchesTheContract() {
        assertRecordMatchesSchema(RecentTransaction.class, "RecentTransaction");
    }

    @Test
    @DisplayName("TransactionToScore's fields are exactly the contract's")
    void transactionToScoreMatchesTheContract() {
        assertRecordMatchesSchema(TransactionToScore.class, "TransactionToScore");
    }

    @Test
    @DisplayName("AccountContext's fields are exactly the contract's")
    void accountContextMatchesTheContract() {
        assertRecordMatchesSchema(AccountContext.class, "AccountContext");
    }

    @Test
    @DisplayName("ScoreRequest's fields are exactly the contract's")
    void scoreRequestMatchesTheContract() {
        assertRecordMatchesSchema(ScoreRequest.class, "ScoreRequest");
    }

    @Test
    @DisplayName("the recentTransactions cap is the contract's maxItems, not a convention")
    void theCapIsTheContractsMaxItems() {
        Object maxItems = properties("AccountContext").get("recentTransactions").get("maxItems");

        assertThat(maxItems)
                .as("a cap larger than the contract's would build requests the service must reject, "
                        + "and one smaller would silently discard history the model was promised")
                .isEqualTo(ScoringContextProperties.CONTRACT_MAX_RECENT_TRANSACTIONS);
    }

    @Test
    @DisplayName("the context version this assembler stamps satisfies the contract's minimum")
    void theContextVersionIsWithinTheContract() {
        Object minimum = properties("AccountContext").get("contextVersion").get("minimum");

        assertThat(AccountContextAssembler.CONTEXT_VERSION)
                .as("contextVersion is what tells the scoring service which shape it received")
                .isGreaterThanOrEqualTo(((Number) minimum).intValue());
    }

    @Test
    @DisplayName("the enums the payloads carry are exactly the contract's")
    void theEnumsMatchTheContract() {
        assertThat(names(TransactionType.values()))
                .as("a type the contract does not list is a request the service rejects")
                .containsExactlyInAnyOrderElementsOf(enumValues("TransactionType"));

        assertThat(names(TransactionChannel.values()))
                .as("channel drives the deviceless-channel rule in the feature pipeline")
                .containsExactlyInAnyOrderElementsOf(enumValues("TransactionChannel"));
    }

    // ----------------------------------------------------------------------- //

    private static void assertRecordMatchesSchema(Class<?> type, String schemaName) {
        List<String> inRecord = new ArrayList<>();
        for (RecordComponent component : type.getRecordComponents()) {
            inRecord.add(component.getName());
        }

        assertThat(inRecord)
                .as(
                        "%s declares additionalProperties: false, so a field here and not there is a "
                                + "request the scoring service is required to reject",
                        schemaName)
                .containsExactlyInAnyOrderElementsOf(properties(schemaName).keySet());
    }

    private static Set<String> names(Enum<?>[] values) {
        return Arrays.stream(values)
                .map(Enum::name)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    @SuppressWarnings("unchecked")
    private static List<String> enumValues(String schemaName) {
        return (List<String>) schemas().get(schemaName).get("enum");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> properties(String schemaName) {
        Map<String, Object> schema = schemas().get(schemaName);
        assertThat(schema).as("%s is not defined in %s", schemaName, CONTRACT).isNotNull();
        return (Map<String, Map<String, Object>>) schema.get("properties");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> schemas() {
        // Relative to the module, so this works in CI and in a clone at any
        // path. Absent means fail, never skip: see the class comment.
        Path repositoryRoot = Path.of("").toAbsolutePath().getParent().getParent();
        Path path = repositoryRoot.resolve(CONTRACT);
        try {
            Map<String, Object> document = new Yaml().load(Files.readString(path, StandardCharsets.UTF_8));
            Map<String, Object> components = (Map<String, Object>) document.get("components");
            return (Map<String, Map<String, Object>>) components.get("schemas");
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
    }
}
