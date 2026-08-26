/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.la3679.sentinelflow.api.support.AbstractPostgresTest;

/**
 * What the migrations produced, asserted against the database rather than against the files.
 *
 * <p>This suite covers the facts a constraint test cannot reach: that every migration applied, that
 * the PostgreSQL version actually in use supports what the schema assumes, and that the indexes
 * chosen for a measured query pattern are the ones that exist. An index is not enforced by
 * anything, so nothing else would notice if one silently stopped being created.
 */
class MigrationIT extends AbstractPostgresTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("every migration applied, in order, and none failed")
    void allMigrationsApplied() {
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank", String.class);
        // Every migration this module ships, in order. Adding one without
        // adding it here fails, which is the point: a migration that ran but
        // that nothing expected is exactly what an accidental commit looks like.
        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7");

        Integer failures =
                jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success = false", Integer.class);
        assertThat(failures).isZero();
    }

    @Test
    @DisplayName("all fifteen domain tables exist")
    void everyDomainTableExists() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                  AND table_name <> 'flyway_schema_history'
                ORDER BY table_name
                """, String.class);

        assertThat(tables)
                .containsExactlyInAnyOrder(
                        "accounts",
                        "alert_actions",
                        "alerts",
                        "analyst_feedback",
                        "audit_log",
                        "customers",
                        "merchants",
                        "model_registry",
                        "outbox_events",
                        "processed_events",
                        "risk_assessments",
                        "roles",
                        "transactions",
                        "user_roles",
                        "users");
    }

    @Test
    @DisplayName("the database is PostgreSQL 18 or later, which uuidv7() requires")
    void postgresIsRecentEnoughForUuidV7() {
        Integer major = jdbc.queryForObject("SELECT current_setting('server_version_num')::int / 10000", Integer.class);
        assertThat(major).isGreaterThanOrEqualTo(18);

        // The DEFAULT on every primary key. A direct SQL insert - a seed
        // loader, a fixture, a psql session - relies on it to avoid
        // introducing a v4 key and losing the index locality UUIDv7 was chosen
        // for (ADR-0007).
        String generated = jdbc.queryForObject("SELECT uuidv7()::text", String.class);
        assertThat(generated).isNotNull();
        assertThat(generated.charAt(14)).isEqualTo('7');
    }

    @Test
    @DisplayName("money columns are NUMERIC, never a floating-point type")
    void moneyColumnsAreExact() {
        List<String> types = jdbc.queryForList("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND (table_name, column_name) IN (('accounts', 'balance'), ('transactions', 'amount'))
                """, String.class);

        assertThat(types).hasSize(2).allMatch("numeric"::equals);
    }

    @Test
    @DisplayName("every timestamp column carries its zone")
    void timestampsAreZoned() {
        // A timestamp without a zone is a timestamp whose meaning depends on
        // the reader, which for an audit trail is not a meaning at all.
        List<String> naive = jdbc.queryForList("""
                SELECT table_name || '.' || column_name FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name <> 'flyway_schema_history'
                  AND data_type = 'timestamp without time zone'
                """, String.class);

        assertThat(naive).isEmpty();
    }

    @Test
    @DisplayName("the partial and expression indexes the read paths depend on exist")
    void deliberateIndexesExist() {
        List<String> indexes =
                jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'", String.class);

        assertThat(indexes)
                .contains(
                        // The relay's only query: due events, and nothing else.
                        "outbox_events_due_idx",
                        // The alert queue, the most frequent read in the product.
                        "alerts_queue_idx",
                        // One analyst's desk.
                        "alerts_assignee_open_idx",
                        // Every velocity feature and every account timeline.
                        "transactions_account_occurred_idx",
                        // The scan that looks for work to do.
                        "transactions_pending_idx",
                        // At most one active model, enforced by the database.
                        "model_registry_single_active_idx");
    }

    @Test
    @DisplayName("the outbox due index stays partial, or it costs a write on every published row")
    void outboxDueIndexIsPartial() {
        String definition = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'outbox_events_due_idx'",
                String.class);

        assertThat(definition).contains("WHERE").contains("PENDING");
    }
}
