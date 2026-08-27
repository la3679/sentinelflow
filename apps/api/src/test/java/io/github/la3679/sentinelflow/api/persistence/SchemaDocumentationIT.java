/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.la3679.sentinelflow.api.support.AbstractPostgresTest;

/**
 * Keeps the entity-relationship diagram honest.
 *
 * <p>A diagram that has quietly stopped describing the schema is worse than no diagram: a reader
 * trusts it, and nothing about looking at it reveals that it is wrong. Nothing else in this build
 * would notice a table added without a corresponding entity block, because a diagram is prose as far
 * as the compiler is concerned.
 *
 * <p>This asserts the set of entity blocks in the Mermaid {@code erDiagram} equals the set of tables
 * in the database. It deliberately does not check columns: {@code DATA_MODEL.md} states that the
 * migrations are authoritative for those and does not repeat them, precisely so that there is no
 * duplicated column list to go stale.
 */
class SchemaDocumentationIT extends AbstractPostgresTest {

    /**
     * An entity block header inside the Mermaid diagram: a table name at the start of a line,
     * followed by an opening brace. Relationship lines are excluded because they carry {@code ||--o}
     * between two names and never open a block.
     */
    private static final Pattern ENTITY_BLOCK = Pattern.compile("(?m)^\\s{4}([a-z_]+)\\s*\\{\\s*$");

    @Autowired
    private JdbcTemplate jdbc;

    private static Path documentationRoot() {
        // The module runs from apps/api, so the repository root is two levels
        // up. Resolved rather than hard-coded as an absolute path, so this
        // works in CI and in a clone at any location.
        return Path.of("").toAbsolutePath().getParent().getParent().resolve("docs/architecture");
    }

    private static String read(String name) {
        Path path = documentationRoot().resolve(name);
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
    }

    @Test
    @DisplayName("the ER diagram describes exactly the tables that exist")
    void erDiagramMatchesTheSchema() {
        Set<String> documented = new TreeSet<>();
        Matcher matcher = ENTITY_BLOCK.matcher(read("DATA_MODEL.md"));
        while (matcher.find()) {
            documented.add(matcher.group(1));
        }

        List<String> actual = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                  AND table_name <> 'flyway_schema_history'
                """, String.class);

        assertThat(documented)
                .as("every table has an entity block in docs/architecture/DATA_MODEL.md, and no block "
                        + "describes a table that no longer exists")
                .containsExactlyInAnyOrderElementsOf(actual);
    }

    @Test
    @DisplayName("the flow document separates what runs from what is still design")
    void flowDocumentDoesNotClaimToBeImplemented() {
        // A sequence diagram is a claim that the pipeline works unless the page
        // says which parts of it do not. This asserts the page still draws that
        // line rather than asserting where the line is: the strings below move
        // every time a phase lands, and that is the point - the assertion fails
        // when a phase ships and nobody updated the page, which is exactly when
        // the diagram starts lying.
        String flow = read("TRANSACTION_TO_ALERT.md");

        assertThat(flow)
                .as("the status banner names the phase the page describes")
                .contains("Phase 4 status");
        assertThat(flow)
                .as("alert creation is the part of this flow that does not run yet, and the page "
                        + "has to say so where a reader will see it")
                .contains("Alert creation is Phase 5");
    }
}
